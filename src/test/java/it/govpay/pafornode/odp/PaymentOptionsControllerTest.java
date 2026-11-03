package it.govpay.pafornode.odp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.common.utils.IuvUtils;
import it.govpay.common.utils.IuvUtils.IdentificativiPagamento;
import it.govpay.pafornode.odp.api.model.EnumPo;
import it.govpay.pafornode.odp.api.model.ErrorResponse;
import it.govpay.pafornode.odp.api.model.PaymentOptionsResponse;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Verifica {@code getPaymentOptionsByNoticeNumber} a livello di integrazione (contesto Spring
 * completo, richiesta REST reale via HTTP) — stesso principio di {@code PaForNodeEndpointTest}:
 * catena vera (routing/autenticazione/business logic/persistenza/serializzazione JSON), non
 * solo il controller invocato come metodo Java. L'aggregato con piu' opzioni sorelle e'
 * persistito direttamente via {@link EntityManager} (non tramite
 * {@code PosizioneDebitoriaService#crea}, che non supporta piu' di un'opzione nella stessa
 * chiamata) per poter mettere ATTIVATA/ANNULLATA fin dalla creazione.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentOptionsControllerTest {

	private static final String COD_DOMINIO = "12345678901";
	private static final String COD_INTERMEDIARIO = "BROKER-TEST";
	private static final String COD_STAZIONE = "STAZIONE-TEST";
	private static final String PRINCIPAL_INTERMEDIARIO = "CN=broker-test.example.it";
	private static final String HEADER_PRINCIPAL = "X-Pre-Auth-User";

	@LocalServerPort
	private int port;

	private final RestTemplate restTemplate = new RestTemplate();

	@Autowired
	private DominioRepository dominioRepository;

	@Autowired
	private IntermediarioRepository intermediarioRepository;

	@Autowired
	private StazioneRepository stazioneRepository;

	@Autowired
	private ApplicazioneRepository applicazioneRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private Long idDominio;
	private Long idApplicazione;

	@BeforeEach
	void creaAnagrafiche() {
		DominioEntity dominio = dominioRepository.save(DominioEntity.builder().codDominio(COD_DOMINIO)
				.ragioneSociale("Comune di Test").abilitato(true).intermediato(false).scaricaFr(false).auxDigit(1)
				.build());
		idDominio = dominio.getId();

		IntermediarioEntity intermediario = intermediarioRepository.save(IntermediarioEntity.builder()
				.codIntermediario(COD_INTERMEDIARIO).denominazione("Intermediario di test")
				.principal(PRINCIPAL_INTERMEDIARIO).abilitato(true).build());

		StazioneEntity stazione = stazioneRepository.save(StazioneEntity.builder().codStazione(COD_STAZIONE)
				.abilitato(true).applicationCode(1).intermediario(intermediario).build());

		dominio.setStazione(stazione);
		dominioRepository.save(dominio);

		ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-TEST").autoIuv(true).firmaRicevuta("N").trusted(true).idUtenza(1L).build());
		idApplicazione = applicazione.getId();
	}

	@AfterEach
	void pulisci() {
		dominioRepository.deleteAll();
		stazioneRepository.deleteAll();
		intermediarioRepository.deleteAll();
		applicazioneRepository.deleteAll();
	}

	private IdentificativiPagamento nuoviIdentificativi() {
		return IuvUtils.genera(1, "", null, null, System.nanoTime() % 1_000_000_000_000_00L);
	}

	private OpzionePagamento nuovaOpzione(TipologiaOpzionePagamento tipologia, StatoOpzionePagamento stato) {
		OpzionePagamento opzione = new OpzionePagamento();
		opzione.setIdOpzionePagamento(UUID.randomUUID());
		opzione.setTipologia(tipologia);
		opzione.setStato(stato);
		opzione.setDataCreazione(OffsetDateTime.now());
		opzione.setDataUltimoAggiornamento(OffsetDateTime.now());
		return opzione;
	}

	private Pendenza pendenza(IdentificativiPagamento identificativi, double importo, StatoPendenza stato) {
		Pendenza pendenza = new Pendenza();
		pendenza.setIdApplicazione(idApplicazione);
		pendenza.setIdDominio(idDominio);
		pendenza.setIdPendenza("pend-" + identificativi.iuv());
		pendenza.setIdTipoPendenza(1L);
		pendenza.setIdTipoVersamento(1L);
		pendenza.setImporto(importo);
		pendenza.setNumeroAvviso(identificativi.numeroAvviso());
		pendenza.setIuv(identificativi.iuv());
		pendenza.setSrcIuv(identificativi.iuv());
		pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
		pendenza.setDebitoreAnagrafica("Mario Rossi");
		pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
		pendenza.setStato(stato);
		pendenza.setDataCreazione(OffsetDateTime.now());
		pendenza.setDataUltimoAggiornamento(OffsetDateTime.now());
		return pendenza;
	}

	/**
	 * Una posizione con tre opzioni (DISPONIBILE, ATTIVATA, ANNULLATA): la risposta deve
	 * contenere solo le prime due, qualunque sia il NAV richiesto tra quelli DISPONIBILE/
	 * ATTIVATA — deciso con l'utente (il NAV serve solo a individuare la posizione).
	 */
	private IdentificativiPagamento[] creaPosizioneConTreOpzioni() {
		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setIdApplicazione(idApplicazione);
		posizione.setCodDocumento("doc-odp-" + System.nanoTime());
		posizione.setIdDominio(idDominio);
		posizione.setDescrizione("Descrizione della posizione di test");
		posizione.setDataCreazione(OffsetDateTime.now());
		posizione.setDataUltimoAggiornamento(OffsetDateTime.now());

		SoggettoDebitore soggetto = new SoggettoDebitore();
		soggetto.setTipo(TipoSoggetto.F);
		soggetto.setIdentificativo("RSSMRA80A01H501U");
		soggetto.setAnagrafica("Mario Rossi");
		posizione.addSoggettoDebitore(soggetto);

		IdentificativiPagamento idDisponibile = nuoviIdentificativi();
		OpzionePagamento disponibile = nuovaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA,
				StatoOpzionePagamento.DISPONIBILE);
		posizione.addOpzionePagamento(disponibile);
		disponibile.addPendenza(pendenza(idDisponibile, 100, StatoPendenza.NON_ESEGUITO));

		IdentificativiPagamento idAttivata = nuoviIdentificativi();
		OpzionePagamento attivata = nuovaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA,
				StatoOpzionePagamento.ATTIVATA);
		posizione.addOpzionePagamento(attivata);
		attivata.addPendenza(pendenza(idAttivata, 50, StatoPendenza.ESEGUITO));

		IdentificativiPagamento idAnnullata = nuoviIdentificativi();
		OpzionePagamento annullata = nuovaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA,
				StatoOpzionePagamento.ANNULLATA);
		posizione.addOpzionePagamento(annullata);
		annullata.addPendenza(pendenza(idAnnullata, 30, StatoPendenza.ANNULLATO));

		// Transazione esplicita e committata: il controller legge via una richiesta HTTP
		// reale, su una connessione diversa da quella del test — una persist dentro la sola
		// transazione implicita del metodo di test (se ce ne fosse una) resterebbe invisibile
		// al thread del server. Stesso pattern di PaForNodeEndpointTest.
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			entityManager.persist(posizione);
			entityManager.flush();
		});

		return new IdentificativiPagamento[] { idDisponibile, idAttivata, idAnnullata };
	}

	private ResponseEntity<PaymentOptionsResponse> invia(String numeroAvviso) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HEADER_PRINCIPAL, PRINCIPAL_INTERMEDIARIO);
		String url = "http://localhost:" + port + "/payment-options/organizations/" + COD_DOMINIO + "/notices/"
				+ numeroAvviso;
		return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), PaymentOptionsResponse.class);
	}

	@Test
	void restituisceSoloLeOpzioniDisponibiliOAttivateDellaPosizione() {
		IdentificativiPagamento[] identificativi = creaPosizioneConTreOpzioni();

		ResponseEntity<PaymentOptionsResponse> risposta = invia(identificativi[0].numeroAvviso());

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		PaymentOptionsResponse body = risposta.getBody();
		assertThat(body.getOrganizationFiscalCode()).isEqualTo(COD_DOMINIO);
		assertThat(body.getCompanyName()).isEqualTo("Comune di Test");
		assertThat(body.getPaymentOptions()).hasSize(2);
		assertThat(body.getPaymentOptions()).extracting(po -> po.getStatus())
				.containsExactly(EnumPo.PO_UNPAID, EnumPo.PO_PAID);
		assertThat(body.getPaymentOptions().get(0).getInstallments().get(0).getNav())
				.isEqualTo(identificativi[0].numeroAvviso());
	}

	@Test
	void stessaRispostaRichiedendoIlNavDellOpzioneAttivata() {
		IdentificativiPagamento[] identificativi = creaPosizioneConTreOpzioni();

		ResponseEntity<PaymentOptionsResponse> risposta = invia(identificativi[1].numeroAvviso());

		assertThat(risposta.getBody().getPaymentOptions()).hasSize(2);
	}

	@Test
	void navSconosciutoRispondeConOdp107() {
		IdentificativiPagamento sconosciuti = nuoviIdentificativi();
		HttpHeaders headers = new HttpHeaders();
		headers.add(HEADER_PRINCIPAL, PRINCIPAL_INTERMEDIARIO);
		String url = "http://localhost:" + port + "/payment-options/organizations/" + COD_DOMINIO + "/notices/"
				+ sconosciuti.numeroAvviso();

		assertThatThrownBy(
				() -> restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), String.class))
				.isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
					assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
					ErrorResponse body = e.getResponseBodyAs(ErrorResponse.class);
					assertThat(body.getAppErrorCode()).isEqualTo("ODP-107");
					assertThat(body.getHttpStatusCode()).isEqualTo(404);
				});
	}

	/**
	 * Il NAV dell'opzione ANNULLATA individua comunque la posizione: la risposta e' la stessa
	 * di qualunque altro NAV della stessa posizione (le due opzioni ancora valide), non un
	 * fault — corretto durante l'implementazione (vedi Javadoc di
	 * {@code GetPaymentOptionsService}), coerente con "il NAV serve solo a individuare la
	 * posizione" deciso con l'utente.
	 */
	@Test
	void navDellOpzioneAnnullataRestituisceComunqueLeOpzioniAncoraValide() {
		IdentificativiPagamento[] identificativi = creaPosizioneConTreOpzioni();

		ResponseEntity<PaymentOptionsResponse> risposta = invia(identificativi[2].numeroAvviso());

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody().getPaymentOptions()).hasSize(2);
	}

	/**
	 * Quando la posizione non ha PROPRIO NESSUNA opzione DISPONIBILE/ATTIVATA (qui: un'unica
	 * opzione, ANNULLATA, nessuna sorella), il fallback a {@code verificaStatoPagabile} da' un
	 * fault specifico sullo stato della pendenza — a differenza del caso sopra, qui non c'e'
	 * nulla da restituire comunque.
	 */
	@Test
	void posizioneSenzaAlcunaOpzioneDisponibileRispondeConOdp111() {
		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setIdApplicazione(idApplicazione);
		posizione.setCodDocumento("doc-odp-ann-" + System.nanoTime());
		posizione.setIdDominio(idDominio);
		posizione.setDescrizione("Posizione con sola opzione annullata");
		posizione.setDataCreazione(OffsetDateTime.now());
		posizione.setDataUltimoAggiornamento(OffsetDateTime.now());
		SoggettoDebitore soggetto = new SoggettoDebitore();
		soggetto.setTipo(TipoSoggetto.F);
		soggetto.setIdentificativo("RSSMRA80A01H501U");
		soggetto.setAnagrafica("Mario Rossi");
		posizione.addSoggettoDebitore(soggetto);

		IdentificativiPagamento idAnnullata = nuoviIdentificativi();
		OpzionePagamento annullata = nuovaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA,
				StatoOpzionePagamento.ANNULLATA);
		posizione.addOpzionePagamento(annullata);
		annullata.addPendenza(pendenza(idAnnullata, 30, StatoPendenza.ANNULLATO));

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			entityManager.persist(posizione);
			entityManager.flush();
		});

		HttpHeaders headers = new HttpHeaders();
		headers.add(HEADER_PRINCIPAL, PRINCIPAL_INTERMEDIARIO);
		String url = "http://localhost:" + port + "/payment-options/organizations/" + COD_DOMINIO + "/notices/"
				+ idAnnullata.numeroAvviso();

		assertThatThrownBy(
				() -> restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), String.class))
				.isInstanceOfSatisfying(HttpClientErrorException.class, e -> {
					assertThat(e.getStatusCode().value()).isEqualTo(422);
					ErrorResponse body = e.getResponseBodyAs(ErrorResponse.class);
					assertThat(body.getAppErrorCode()).isEqualTo("ODP-111");
				});
	}
}
