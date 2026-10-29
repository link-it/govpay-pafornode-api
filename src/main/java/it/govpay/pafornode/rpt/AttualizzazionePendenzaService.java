package it.govpay.pafornode.rpt;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
		OffsetDateTime oggi = OffsetDateTime.now(clock);
		if (pendenza.getDataValidita() == null || pendenza.getDataValidita().isAfter(oggi)) {
			return;
		}
		ApplicazioneEntity applicazione = applicazioneRepository.findById(pendenza.getIdApplicazione())
				.orElseThrow(() -> new IllegalStateException(
						"Applicazione [" + pendenza.getIdApplicazione() + "] della pendenza non trovata."));

		boolean attualizzata = false;
		String codConnettoreVerifica = applicazione.getCodConnettoreIntegrazione();
		if (codConnettoreVerifica != null && !codConnettoreVerifica.isBlank()) {
			riacquisisci(pendenza, applicazione, dominio, codConnettoreVerifica, codDominio);
			attualizzata = true;
		}
		if (pendenza.isSendAbilitato() && dominio.getCodConnettoreSend() != null) {
			attualizzaSend(pendenza, applicazione, dominio, OffsetDateTime.now(clock));
			attualizzata = true;
		}
		if (!attualizzata && attualizzazioneMandatoria) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCADUTO, codDominio);
		}
	}

	private void riacquisisci(Pendenza pendenza, ApplicazioneEntity applicazione, DominioEntity dominio,
			String codConnettoreVerifica, String codDominio) {
		PendenzaVerificata risposta = verificaClient.getAvviso(codConnettoreVerifica, dominio.getCodDominio(),
				pendenza.getNumeroAvviso());
		PendenzaVerificataMapper.verificaEsito(risposta, codDominio);
		AggiornamentoPendenza comando = pendenzaVerificataMapper.toAggiornamentoPendenza(pendenza,
				risposta.getPendenza(), dominio.getId());
		posizioneDebitoriaService.aggiornaPendenza(applicazione.getCodApplicazione(), pendenza.getIdPendenza(),
				comando);
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
