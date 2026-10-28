package it.govpay.pafornode.rpt;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.client.converter.ConnettoreConverter;
import it.govpay.common.client.model.Connettore;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.ConnettoreEntity;
import it.govpay.common.entity.VersioneApi;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.ConnettoreEntityRepository;
import it.govpay.pendenze.entity.Notifica;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.model.StatoSpedizioneNotifica;
import it.govpay.pendenze.model.TipoNotifica;
import it.govpay.pendenze.repository.NotificaRepository;

/**
 * Accodamento della notifica di attivazione RPT verso il connettore di integrazione
 * dell'applicazione, analisi completa del legacy ({@code it.govpay.core.business.Notifica#inserisciNotifica})
 * nel design doc: nessuna notifica se l'applicazione non ha un connettore configurato, e per
 * il tipo ATTIVAZIONE (a differenza di RICEVUTA, non ancora implementata) solo se il
 * connettore e' in versione {@code REST_1}.
 *
 * <p><b>Solo accodamento</b>: la spedizione effettiva (chiamata al connettore, con
 * ritentativi) non e' ancora implementata — la riga resta {@code DA_SPEDIRE}, candidata per
 * un futuro processo di spedizione, invece del tentativo sincrono immediato del legacy.
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

	@Transactional
	public void accoda(Pendenza pendenza, Rpt rpt) {
		ApplicazioneEntity applicazione = applicazioneRepository.findById(pendenza.getIdApplicazione()).orElse(null);
		if (applicazione == null || applicazione.getCodConnettoreIntegrazione() == null
				|| applicazione.getCodConnettoreIntegrazione().isBlank()) {
			return;
		}

		List<ConnettoreEntity> proprieta = connettoreEntityRepository
				.findByCodConnettore(applicazione.getCodConnettoreIntegrazione());
		if (proprieta.isEmpty()) {
			return;
		}
		Connettore connettore = ConnettoreConverter.toModel(proprieta);
		if (connettore.getVersione() != VersioneApi.REST_1) {
			return;
		}

		OffsetDateTime adesso = OffsetDateTime.now(clock);
		Notifica notifica = new Notifica();
		notifica.setIdApplicazione(applicazione.getId());
		notifica.setIdRpt(rpt.getId());
		notifica.setTipo(TipoNotifica.ATTIVAZIONE);
		notifica.setDataCreazione(adesso);
		notifica.setStato(StatoSpedizioneNotifica.DA_SPEDIRE);
		notifica.setDataAggiornamentoStato(adesso);
		notifica.setDataProssimaSpedizione(adesso);
		notificaRepository.save(notifica);
	}
}
