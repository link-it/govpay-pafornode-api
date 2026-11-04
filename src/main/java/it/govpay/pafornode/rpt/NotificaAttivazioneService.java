package it.govpay.pafornode.rpt;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.ConnettoreEntityRepository;
import it.govpay.pendenze.entity.Notifica;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.model.StatoSpedizioneNotifica;
import it.govpay.pendenze.model.TipoNotifica;
import it.govpay.pendenze.repository.NotificaRepository;

/**
 * Accodamento della notifica RPT di tipo RICEVUTA verso il connettore di integrazione
 * dell'applicazione, analisi completa del legacy
 * ({@code it.govpay.core.business.Notifica#inserisciNotifica}) nel design doc: nessuna
 * notifica se l'applicazione non ha un connettore configurato, nessun filtro per versione
 * del connettore — accodata sempre, sia su connettore v1 che v2 (commento del legacy stesso:
 * "la versione V2 delle api client prevede solamente l'invio delle notifiche di tipo
 * ricevuta").
 *
 * <p><b>Nessuna notifica ATTIVAZIONE</b> (issue #6, 2026-11-03): l'attivazione non scrive
 * piu' nessuna RPT (vedi Javadoc di classe di {@code PaGetPaymentActivationService}), quindi
 * non ha piu' niente da notificare qui — decisione esplicita dell'issue, da segnalare nelle
 * note di rilascio perche' alcune installazioni potrebbero usarla. Il nome della classe
 * resta per continuita' storica.</p>
 *
 * <p><b>Solo accodamento</b>: la spedizione effettiva verso l'EC e' responsabilita' di
 * {@code govpay-notify-batch} (progetto separato, gia' esistente — legge dalla stessa
 * tabella {@code notifiche} le righe {@code DA_SPEDIRE}), non di questo servizio: la riga
 * resta {@code DA_SPEDIRE}, candidata alla prossima esecuzione di quel batch.</p>
 */
@Service
public class NotificaAttivazioneService {

	private final ApplicazioneRepository applicazioneRepository;
	private final ConnettoreEntityRepository connettoreEntityRepository;
	private final NotificaRepository notificaRepository;
	private final Clock clock;

	public NotificaAttivazioneService(ApplicazioneRepository applicazioneRepository,
			ConnettoreEntityRepository connettoreEntityRepository, NotificaRepository notificaRepository,
			Clock clock) {
		this.applicazioneRepository = applicazioneRepository;
		this.connettoreEntityRepository = connettoreEntityRepository;
		this.notificaRepository = notificaRepository;
		this.clock = clock;
	}

	/**
	 * Accoda la notifica RICEVUTA — porting di
	 * {@code CtReceiptUtils.acquisisciRT}/{@code CtReceiptV2Utils.acquisisciRT} (accodata
	 * incondizionatamente dopo l'acquisizione di una ricevuta, sia per esito OK sia KO).
	 * Nessun filtro per versione del connettore (vedi Javadoc di classe): basta che un
	 * connettore sia configurato.
	 */
	@Transactional
	public void accodaRicevuta(Pendenza pendenza, Rpt rpt) {
		ApplicazioneEntity applicazione = applicazioneRepository.findById(pendenza.getIdApplicazione()).orElse(null);
		if (applicazione == null || applicazione.getCodConnettoreIntegrazione() == null
				|| applicazione.getCodConnettoreIntegrazione().isBlank()) {
			return;
		}
		if (connettoreEntityRepository.findByCodConnettore(applicazione.getCodConnettoreIntegrazione()).isEmpty()) {
			return;
		}

		OffsetDateTime adesso = OffsetDateTime.now(clock);
		Notifica notifica = new Notifica();
		notifica.setIdApplicazione(applicazione.getId());
		notifica.setIdRpt(rpt.getId());
		notifica.setTipo(TipoNotifica.RICEVUTA);
		notifica.setDataCreazione(adesso);
		notifica.setStato(StatoSpedizioneNotifica.DA_SPEDIRE);
		notifica.setDataAggiornamentoStato(adesso);
		notifica.setDataProssimaSpedizione(adesso);
		notificaRepository.save(notifica);
	}
}
