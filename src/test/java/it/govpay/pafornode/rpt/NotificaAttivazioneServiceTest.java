package it.govpay.pafornode.rpt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.ConnettoreEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.ConnettoreEntityRepository;
import it.govpay.pafornode.Application;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.entity.Notifica;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.TipoNotifica;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.NotificaRepository;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * {@code @Transactional} di classe, come {@code AcquisizioneRicevutaServiceTest}: senza
 * rollback automatico, le {@link Notifica} inserite da un test restano committed sulla
 * stessa istanza H2 condivisa dal contesto Spring (stessa configurazione di {@code
 * @SpringBootTest} di altri test, es. {@code RptAttivazioneServiceTest}) e sono visibili
 * ai test eseguiti dopo — sia nella stessa classe (ordine JUnit non garantito) sia in una
 * classe diversa che riusa il contesto cache.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
@Transactional
class NotificaAttivazioneServiceTest {

	@Autowired
	private NotificaAttivazioneService service;

	@Autowired
	private RptAttivazioneService rptAttivazioneService;

	@Autowired
	private ApplicazioneRepository applicazioneRepository;

	@Autowired
	private ConnettoreEntityRepository connettoreEntityRepository;

	@Autowired
	private NotificaRepository notificaRepository;

	@Autowired
	private PosizioneDebitoriaService posizioneDebitoriaService;

	/** RPT realmente persistita (vincolo FK fisico {@code notifiche.id_rpt -> rpt.id}). */
	private Rpt rptPersistita(Long idApplicazione, String iuv) {
		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setIdApplicazione(idApplicazione);
		posizione.setCodDocumento("doc-" + iuv);
		posizione.setIdDominio(1L);
		posizione.setDescrizione("Descrizione della posizione di test");

		SoggettoDebitore soggetto = new SoggettoDebitore();
		soggetto.setTipo(TipoSoggetto.F);
		soggetto.setIdentificativo("RSSMRA80A01H501U");
		soggetto.setAnagrafica("Mario Rossi");
		posizione.addSoggettoDebitore(soggetto);

		OpzionePagamento opzione = new OpzionePagamento();
		opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
		posizione.addOpzionePagamento(opzione);

		Pendenza pendenza = new Pendenza();
		pendenza.setIdApplicazione(idApplicazione);
		pendenza.setIdPendenza("pend-" + iuv);
		pendenza.setIdTipoPendenza(1L);
		pendenza.setIdTipoVersamento(1L);
		pendenza.setImporto(10.0);
		pendenza.setIuv(iuv);
		pendenza.setSrcIuv(iuv);
		pendenza.setNumeroAvviso("3" + iuv);
		pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
		pendenza.setDebitoreAnagrafica("Mario Rossi");
		pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
		pendenza.setStato(it.govpay.pendenze.model.StatoPendenza.NON_ESEGUITO);
		opzione.addPendenza(pendenza);

		VocePendenza voce = new VocePendenza();
		voce.setIdVocePendenza("voce-" + iuv);
		voce.setImporto(10.0);
		voce.setDescrizione("test");
		voce.setIndice(1);
		voce.setStato(it.govpay.pendenze.model.StatoVocePendenza.NON_ESEGUITO);
		voce.setIdTributo(1L);
		pendenza.addVocePendenza(voce);

		PosizioneDebitoria persistita = posizioneDebitoriaService.crea(DatiPosizioneDebitoria.da(posizione));
		Pendenza pendenzaPersistita = persistita.getOpzioniPagamento().get(0).getPendenze().get(0);

		DominioEntity dominio = new DominioEntity();
		dominio.setId(1L);
		dominio.setCodDominio("12345678901");
		StazioneEntity stazione = new StazioneEntity();
		stazione.setCodStazione("STAZIONE-TEST");

		return rptAttivazioneService.attiva(pendenzaPersistita, dominio, stazione, "123456789", new CtPaymentPA());
	}

	@Test
	void nonAccodaNullaSeLApplicazioneNonHaUnConnettoreConfigurato() {
		ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-SENZA-CONNETTORE").autoIuv(true).firmaRicevuta("N").trusted(true).idUtenza(10L)
				.build());
		Rpt rpt = rptPersistita(applicazione.getId(), "00000000000000000010");

		service.accoda(pendenzaFittizia(applicazione.getId()), rpt);

		assertThat(notificaRepository.findAll()).isEmpty();
	}

	@Test
	void accodaUnaNotificaDiAttivazioneSeIlConnettoreERestUno() {
		ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-REST1").autoIuv(true).firmaRicevuta("N").trusted(true).idUtenza(11L)
				.codConnettoreIntegrazione("CONN-REST1").build());
		connettoreEntityRepository.save(ConnettoreEntity.builder().codConnettore("CONN-REST1").codProprieta("VERSIONE")
				.valore("REST_1").build());
		Rpt rpt = rptPersistita(applicazione.getId(), "00000000000000000011");

		service.accoda(pendenzaFittizia(applicazione.getId()), rpt);

		Notifica notifica = notificaRepository.findAll().get(0);
		assertThat(notifica.getIdApplicazione()).isEqualTo(applicazione.getId());
		assertThat(notifica.getIdRpt()).isEqualTo(rpt.getId());
		assertThat(notifica.getTipo()).isEqualTo(TipoNotifica.ATTIVAZIONE);
	}

	@Test
	void nonAccodaNullaSeIlConnettoreNonERestUno() {
		ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-REST2").autoIuv(true).firmaRicevuta("N").trusted(true).idUtenza(12L)
				.codConnettoreIntegrazione("CONN-REST2").build());
		connettoreEntityRepository.save(ConnettoreEntity.builder().codConnettore("CONN-REST2").codProprieta("VERSIONE")
				.valore("REST_2").build());
		Rpt rpt = rptPersistita(applicazione.getId(), "00000000000000000012");

		service.accoda(pendenzaFittizia(applicazione.getId()), rpt);

		assertThat(notificaRepository.findAll()).isEmpty();
	}

	@Test
	void accodaRicevutaNonAccodaNullaSeLApplicazioneNonHaUnConnettoreConfigurato() {
		ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-RIC-SENZA-CONNETTORE").autoIuv(true).firmaRicevuta("N").trusted(true)
				.idUtenza(20L).build());
		Rpt rpt = rptPersistita(applicazione.getId(), "00000000000000000020");

		service.accodaRicevuta(pendenzaFittizia(applicazione.getId()), rpt);

		assertThat(notificaRepository.findAll()).isEmpty();
	}

	@Test
	void accodaRicevutaAncheSeIlConnettoreERestUno() {
		ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-RIC-REST1").autoIuv(true).firmaRicevuta("N").trusted(true).idUtenza(21L)
				.codConnettoreIntegrazione("CONN-RIC-REST1").build());
		connettoreEntityRepository.save(ConnettoreEntity.builder().codConnettore("CONN-RIC-REST1")
				.codProprieta("VERSIONE").valore("REST_1").build());
		Rpt rpt = rptPersistita(applicazione.getId(), "00000000000000000021");

		service.accodaRicevuta(pendenzaFittizia(applicazione.getId()), rpt);

		Notifica notifica = notificaRepository.findAll().get(0);
		assertThat(notifica.getIdApplicazione()).isEqualTo(applicazione.getId());
		assertThat(notifica.getIdRpt()).isEqualTo(rpt.getId());
		assertThat(notifica.getTipo()).isEqualTo(TipoNotifica.RICEVUTA);
	}

	/**
	 * Differenza chiave con {@link #nonAccodaNullaSeIlConnettoreNonERestUno} (ATTIVAZIONE):
	 * RICEVUTA si accoda ANCHE su connettore v2 — nessun filtro di versione, vedi Javadoc
	 * di classe ("la versione V2 delle api client prevede solamente l'invio delle
	 * notifiche di tipo ricevuta").
	 */
	@Test
	void accodaRicevutaAncheSeIlConnettoreERestDue() {
		ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-RIC-REST2").autoIuv(true).firmaRicevuta("N").trusted(true).idUtenza(22L)
				.codConnettoreIntegrazione("CONN-RIC-REST2").build());
		connettoreEntityRepository.save(ConnettoreEntity.builder().codConnettore("CONN-RIC-REST2")
				.codProprieta("VERSIONE").valore("REST_2").build());
		Rpt rpt = rptPersistita(applicazione.getId(), "00000000000000000022");

		service.accodaRicevuta(pendenzaFittizia(applicazione.getId()), rpt);

		Notifica notifica = notificaRepository.findAll().get(0);
		assertThat(notifica.getTipo()).isEqualTo(TipoNotifica.RICEVUTA);
	}

	private Pendenza pendenzaFittizia(Long idApplicazione) {
		Pendenza pendenza = new Pendenza();
		pendenza.setIdApplicazione(idApplicazione);
		return pendenza;
	}
}
