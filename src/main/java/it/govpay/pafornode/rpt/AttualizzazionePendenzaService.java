package it.govpay.pafornode.rpt;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.ec.client.beans.PendenzaVerificata;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.send.SendClient;
import it.govpay.pafornode.verifica.PendenzaVerificataMapper;
import it.govpay.pafornode.verifica.VerificaClient;
import it.govpay.pendenze.comando.AggiornamentoPendenza;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * Attualizzazione della pendenza per validita' scaduta (§3ter/§3quinquies del design doc),
 * analisi completa del legacy ({@code VersamentoUtils.aggiornaVersamento}) nel design doc.
 *
 * <p>Due rami, nello stesso ordine del legacy: se l'applicazione ha un connettore di
 * verifica configurato, riacquisizione live della pendenza ({@link VerificaClient}/{@link
 * PendenzaVerificataMapper}, aggiornamento in loco via {@code PosizioneDebitoriaService
 * #aggiornaPendenza} — non una nuova posizione, la pendenza esiste gia'); poi, se la
 * pendenza (anche appena riacquisita) ha SEND abilitato e il dominio ha un connettore SEND
 * configurato, attualizzazione dell'importo SEND. Nessuna azione se nessuno dei due si
 * applica, a meno che l'attualizzazione sia mandatoria (fault {@code PAA_PAGAMENTO_SCADUTO}).
 *
 * <p>L'acquisizione di una pendenza DEL TUTTO ASSENTE (non solo con validita' scaduta) non e'
 * gestita QUI: richiede di risolvere quale applicazione gestisce il dominio/iuv quando non
 * c'e' ancora una pendenza da cui leggere {@code idApplicazione} ({@link
 * it.govpay.pafornode.verifica.ApplicazioneDominioResolver}) — gestita direttamente in
 * {@link PaGetPaymentActivationService#acquisisciPendenzaAssente}, PRIMA che questo servizio
 * entri in gioco (qui si attualizza una pendenza che esiste gia').
 *
 * <p>Usato solo da {@code paGetPayment} (via {@link PaGetPaymentActivationService}): non
 * ancora da {@code paVerifyPaymentNotice}, che e' {@code @Transactional(readOnly = true)} e
 * richiederebbe prima la stessa estrazione in un servizio scrivente non read-only applicata a
 * {@code paGetPayment} (vedi Javadoc di {@link PaGetPaymentActivationService}).
 */
@Service
public class AttualizzazionePendenzaService {

	private final SendClient sendClient;
	private final VerificaClient verificaClient;
	private final PendenzaVerificataMapper pendenzaVerificataMapper;
	private final PosizioneDebitoriaService posizioneDebitoriaService;
	private final ApplicazioneRepository applicazioneRepository;
	private final Clock clock;
	private final Duration retentionSend;
	private final boolean attualizzazioneMandatoria;

	public AttualizzazionePendenzaService(SendClient sendClient, VerificaClient verificaClient,
			PendenzaVerificataMapper pendenzaVerificataMapper, PosizioneDebitoriaService posizioneDebitoriaService,
			ApplicazioneRepository applicazioneRepository, Clock clock,
			@Value("${govpay.pafornode.attualizzazione.send-retention:PT1H}") Duration retentionSend,
			@Value("${govpay.pafornode.attualizzazione.mandatoria:false}") boolean attualizzazioneMandatoria) {
		this.sendClient = sendClient;
		this.verificaClient = verificaClient;
		this.pendenzaVerificataMapper = pendenzaVerificataMapper;
		this.posizioneDebitoriaService = posizioneDebitoriaService;
		this.applicazioneRepository = applicazioneRepository;
		this.clock = clock;
		this.retentionSend = retentionSend;
		this.attualizzazioneMandatoria = attualizzazioneMandatoria;
	}

	@Transactional
	public void attualizza(Pendenza pendenza, DominioEntity dominio, String codDominio) {
		// Stesso ordine del legacy: nessuna attualizzazione su una pendenza che non e' piu'
		// NON_ESEGUITO (ESEGUITO/ANNULLATO/ecc. hanno gia' un esito definitivo, inutile e
		// potenzialmente dannoso interrogare l'ente) ...
		if (pendenza.getStato() != StatoPendenza.NON_ESEGUITO) {
			return;
		}
		LocalDate oggi = LocalDate.now(clock);
		// ... e nessuna attualizzazione se l'avviso e' GIA' scaduto: evita sia una chiamata
		// inutile all'ente (il fault corretto, PAA_PAGAMENTO_SCADUTO, arriva comunque dal
		// successivo PaForNodeChecks#verificaStatoPagabile) sia il rischio che una risposta
		// dell'ente proroghi una pendenza che avrebbe dovuto restare scaduta.
		OffsetDateTime scadenzaAvviso = pendenza.getDataScadenzaAvviso();
		if (scadenzaAvviso != null && scadenzaAvviso.toLocalDate().isBefore(oggi)) {
			return;
		}
		// Stesso confronto per giorno di PaForNodeChecks#verificaStatoPagabile: dataValidita'
		// e' sempre scritta a mezzanotte dal mapper, un giorno puro — la validita' odierna
		// non deve gia' innescare l'attualizzazione (resta valida fino a fine giornata).
		if (pendenza.getDataValidita() == null || !pendenza.getDataValidita().toLocalDate().isBefore(oggi)) {
			return;
		}
		ApplicazioneEntity applicazione = applicazioneRepository.findById(pendenza.getIdApplicazione())
				.orElseThrow(() -> new IllegalStateException(
						"Applicazione [" + pendenza.getIdApplicazione() + "] della pendenza non trovata."));

		boolean attualizzata = false;
		String codConnettoreVerifica = applicazione.getCodConnettoreIntegrazione();
		boolean verificaConfigurata = codConnettoreVerifica != null && !codConnettoreVerifica.isBlank();
		if (verificaConfigurata) {
			attualizzata = riacquisisci(pendenza, applicazione, dominio, codConnettoreVerifica, codDominio);
			// Controllato PRIMA di procedere a SEND, non dopo: altrimenti un esito positivo
			// di SEND maschererebbe il fallimento della Verifica — quando il connettore di
			// verifica E' configurato (quindi tentato) e l'attualizzazione e' mandatoria, un
			// suo fallimento interrompe il flusso qui, indipendentemente da SEND. Il percorso
			// solo-SEND resta consentito quando il connettore di verifica non e' configurato
			// (verificaConfigurata false): in quel caso non c'e' nulla da aver fallito.
			if (!attualizzata && attualizzazioneMandatoria) {
				throw new NdpException(FaultPa.PAA_PAGAMENTO_SCADUTO, codDominio);
			}
		}
		if (pendenza.isSendAbilitato() && dominio.getCodConnettoreSend() != null) {
			try {
				attualizzaSend(pendenza, applicazione, dominio, OffsetDateTime.now(clock));
				attualizzata = true;
			} catch (RestClientException e) {
				if (attualizzazioneMandatoria) {
					throw new NdpException(FaultPa.PAA_PAGAMENTO_SCADUTO, e.getMessage(), codDominio, e);
				}
			}
		}
		if (!attualizzata && attualizzazioneMandatoria) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCADUTO, codDominio);
		}
	}

	/**
	 * @return {@code true} se la pendenza e' stata effettivamente riacquisita/aggiornata,
	 *         {@code false} se l'ente non ha fornito un dato utilizzabile (connettore non
	 *         raggiungibile, risposta {@code SCONOSCIUTA}, identita' non corrispondente o
	 *         risposta non mappabile) — in questo caso il chiamante procede con i dati
	 *         esistenti, a meno che l'attualizzazione sia mandatoria (fault {@code
	 *         PAA_PAGAMENTO_SCADUTO}, gestito centralmente in {@link #attualizza}).
	 *         {@code ANNULLATA}/{@code SCADUTA} restano bloccanti SEMPRE (lanciano,
	 *         indipendentemente da {@code attualizzazioneMandatoria}: sono un esito del
	 *         pagamento, non un'incertezza recuperabile con i dati esistenti), e un
	 *         eventuale errore di persistenza di {@code aggiornaPendenza} non e' mai
	 *         catturato qui — solo il recupero della risposta e la sua mappatura lo sono.
	 */
	private boolean riacquisisci(Pendenza pendenza, ApplicazioneEntity applicazione, DominioEntity dominio,
			String codConnettoreVerifica, String codDominio) {
		PendenzaVerificata risposta;
		try {
			risposta = verificaClient.getAvviso(codConnettoreVerifica, dominio.getCodDominio(),
					pendenza.getNumeroAvviso());
		} catch (RestClientException e) {
			return false;
		}
		try {
			PendenzaVerificataMapper.verificaEsito(risposta, codDominio);
			PendenzaVerificataMapper.verificaIdentita(risposta.getPendenza(), dominio.getCodDominio(),
					pendenza.getNumeroAvviso(), codDominio);
		} catch (NdpException e) {
			if (e.getFault() == FaultPa.PAA_PAGAMENTO_ANNULLATO || e.getFault() == FaultPa.PAA_PAGAMENTO_SCADUTO) {
				throw e;
			}
			// PAA_PAGAMENTO_SCONOSCIUTO: sia da verificaEsito (risposta SCONOSCIUTA) sia da
			// verificaIdentita (dominio/NAV non corrispondente) — in nessuno dei due casi
			// l'ente ha fornito un dato su cui basarsi, non un esito definitivo come
			// ANNULLATA/SCADUTA.
			return false;
		}
		AggiornamentoPendenza comando;
		try {
			comando = pendenzaVerificataMapper.toAggiornamentoPendenza(pendenza, risposta.getPendenza(),
					dominio.getId());
		} catch (IllegalStateException e) {
			// Risposta non mappabile (voce non riconosciuta, 'voci' assente, ecc.): stesso
			// trattamento del connettore non raggiungibile, non un errore di persistenza.
			return false;
		}
		posizioneDebitoriaService.aggiornaPendenza(applicazione.getCodApplicazione(), pendenza.getIdPendenza(),
				comando);
		return true;
	}

	private void attualizzaSend(Pendenza pendenza, ApplicazioneEntity applicazione, DominioEntity dominio,
			OffsetDateTime oggi) {
		OffsetDateTime ultimoAggiornamento = pendenza.getSendDataAggiornamento();
		if (ultimoAggiornamento != null && ultimoAggiornamento.plus(retentionSend).isAfter(oggi)) {
			return;
		}
		long centesimi = sendClient.recuperaImportoNotifica(dominio.getCodConnettoreSend(), dominio.getCodDominio(),
				pendenza.getNumeroAvviso());
		double euro = centesimi / 100.0;
		posizioneDebitoriaService.registraImportoSend(applicazione.getCodApplicazione(), pendenza.getIdPendenza(),
				euro, oggi);
	}
}
