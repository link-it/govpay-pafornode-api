package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.Mockito;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatusCode;

import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.entity.TipoContabilita;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.common.utils.IuvUtils;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.common.utils.IuvUtils.IdentificativiPagamento;
import it.govpay.common.gde.GdeEventInfo;
import it.govpay.gde.client.beans.EsitoEvento;
import it.govpay.pafornode.gde.PaForNodeGdeService;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.ObjectFactory;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Response;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.pafornode.rpt.CtPaymentPaBuilder;
import it.govpay.pafornode.rpt.CtPaymentPaV2Builder;
import it.govpay.pafornode.rpt.RptAttivazioneService;

/**
 * Verifica {@code paVerifyPaymentNotice} a livello di integrazione (contesto Spring completo,
 * richiesta SOAP reale via HTTP) — stesso principio dei test di integrazione di
 * govpay-pendenze-api: il percorso vero (WSDL/routing/unmarshalling/business logic/
 * persistenza/marshalling), non solo l'endpoint invocato come metodo Java.
 *
 * <p>La catena di sicurezza e' attiva anche qui (metodo HEADER, abilitato solo nel profilo
 * {@code test} — il piu' semplice da simulare via HTTP): ogni richiesta porta l'header con il
 * principal registrato sull'intermediario di fixture, cosi' il confronto principal/idBrokerPA
 * in {@code PaForNodeEndpoint} e' esercitato per davvero, non bypassato da un'autenticazione
 * assente.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaForNodeEndpointTest {

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

	@Autowired
	private PosizioneDebitoriaService posizioneDebitoriaService;

	@PersistenceContext
	private EntityManager entityManager;

	@MockitoSpyBean
	private PendenzaRepository pendenzaRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private PaForNodeGdeService paForNodeGdeService;

	@Autowired
	private CtPaymentPaBuilder ctPaymentPaBuilder;

	@Autowired
	private CtPaymentPaV2Builder ctPaymentPaV2Builder;

	private Long idDominio;
	private Long idApplicazione;

	@BeforeEach
	void creaAnagrafiche() {
		DominioEntity dominio = dominioRepository.save(DominioEntity.builder()
				.codDominio(COD_DOMINIO)
				.ragioneSociale("Comune di Test")
				.abilitato(true)
				.intermediato(false)
				.scaricaFr(false)
				.auxDigit(1)
				.build());
		idDominio = dominio.getId();

		IntermediarioEntity intermediario = intermediarioRepository.save(IntermediarioEntity.builder()
				.codIntermediario(COD_INTERMEDIARIO)
				.denominazione("Intermediario di test")
				.principal(PRINCIPAL_INTERMEDIARIO)
				.abilitato(true)
				.build());

		StazioneEntity stazione = stazioneRepository.save(StazioneEntity.builder()
				.codStazione(COD_STAZIONE)
				.abilitato(true)
				.applicationCode(1)
				.intermediario(intermediario)
				.build());

		// Stazione di default del dominio: letta da RptAttivazioneService#ricostruisci per
		// la ricostruzione "standin" (vedi §3undecies del design doc).
		dominio.setStazione(stazione);
		dominioRepository.save(dominio);

		// Serve una riga reale in anagrafica: AcquisizioneRicevutaService#acquisisci (via
		// PosizioneDebitoriaService#registraEsitoPendenza) risolve codApplicazione da
		// idApplicazione, non accetta un FK arbitrario mai censito.
		ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-TEST")
				.autoIuv(true)
				.firmaRicevuta("N")
				.trusted(true)
				.idUtenza(1L)
				.build());
		idApplicazione = applicazione.getId();
	}

	@AfterEach
	void pulisci() {
		// Dominio prima di stazione: dal momento che il dominio ha una FK verso la sua
		// stazione di default, cancellarla prima lascerebbe un riferimento pendente.
		dominioRepository.deleteAll();
		stazioneRepository.deleteAll();
		intermediarioRepository.deleteAll();
		applicazioneRepository.deleteAll();
	}

	private IdentificativiPagamento creaPendenza(String codDocumento, double importo) {
		IdentificativiPagamento identificativi = IuvUtils.genera(1, "", null, null,
				System.nanoTime() % 1_000_000_000_000_00L);

		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setIdApplicazione(idApplicazione);
		posizione.setCodDocumento(codDocumento);
		posizione.setIdDominio(idDominio);
		posizione.setDescrizione("Descrizione della posizione di test");

		SoggettoDebitore soggetto = new SoggettoDebitore();
		soggetto.setTipo(TipoSoggetto.F);
		soggetto.setIdentificativo("RSSMRA80A01H501U");
		soggetto.setAnagrafica("Mario Rossi");
		posizione.addSoggettoDebitore(soggetto);

		OpzionePagamento opzione = new OpzionePagamento();
		opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
		posizione.addOpzionePagamento(opzione);

		it.govpay.pendenze.entity.Pendenza pendenza = new it.govpay.pendenze.entity.Pendenza();
		pendenza.setIdApplicazione(idApplicazione);
		pendenza.setIdPendenza("pend-" + codDocumento);
		pendenza.setIdTipoPendenza(1L);
		pendenza.setIdTipoVersamento(1L);
		pendenza.setImporto(importo);
		pendenza.setNumeroAvviso(identificativi.numeroAvviso());
		pendenza.setIuv(identificativi.iuv());
		pendenza.setSrcIuv(identificativi.iuv());
		pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
		pendenza.setDebitoreAnagrafica("Mario Rossi");
		pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
		pendenza.setStato(it.govpay.pendenze.model.StatoPendenza.NON_ESEGUITO);
		opzione.addPendenza(pendenza);

		VocePendenza voce = new VocePendenza();
		voce.setIdVocePendenza("voce-" + codDocumento);
		voce.setImporto(importo);
		voce.setDescrizione("test");
		voce.setIndice(1);
		voce.setStato(it.govpay.pendenze.model.StatoVocePendenza.NON_ESEGUITO);
		voce.setIdTributo(1L);
		voce.setTipoContabilita(TipoContabilita.CAPITOLO);
		voce.setCodiceContabilita("001");
		pendenza.addVocePendenza(voce);

		posizioneDebitoriaService.crea(DatiPosizioneDebitoria.da(posizione));
		return identificativi;
	}

	/**
	 * Simula una RPT scritta dal vecchio core PRIMA del passaggio alla 3.11 (issue #6):
	 * questo modulo non scrive piu' nessuna RPT all'attivazione (vedi Javadoc di classe di
	 * {@code PaGetPaymentActivationService}), quindi non e' piu' possibile ottenerne una con
	 * {@code xmlRpt} popolato chiamando {@code paGetPayment}/{@code paGetPaymentV2} su questo
	 * stesso servizio — va persistita a mano, con lo stesso {@code CtPaymentPA}/{@code
	 * CtPaymentPAV2} che l'attivazione avrebbe costruito, per esercitare davvero la
	 * validazione semantica RT-contro-RPT in {@code PaSendRTService}.
	 */
	private Rpt rptAttivataDalVecchioCoreFixture(IdentificativiPagamento identificativi, String ccp,
			String versione) {
		// Tutto nella stessa transazione: CtPaymentPaBuilder/V2 navigano
		// pendenza.getOpzionePagamento().getPosizioneDebitoria() (relazioni lazy) — fuori da
		// una sessione Hibernate attiva solleverebbero LazyInitializationException.
		Rpt[] risultato = new Rpt[1];
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			DominioEntity dominio = dominioRepository.findById(idDominio).orElseThrow();
			Pendenza pendenza = pendenzaRepository.findByIdDominioAndIuv(idDominio, identificativi.iuv())
					.orElseThrow();

			byte[] xmlRpt = versione.equals(RptAttivazioneService.VERSIONE_SANP_240)
					? serializzaPaGetPaymentRes(ctPaymentPaBuilder.costruisci(pendenza, dominio, null))
					: serializzaPaGetPaymentV2Response(ctPaymentPaV2Builder.costruisci(pendenza, dominio, null));

			Rpt rpt = new Rpt();
			rpt.setIdVersamento(pendenza.getId());
			rpt.setIuv(identificativi.iuv());
			rpt.setIur(ccp);
			rpt.setCodDominio(COD_DOMINIO);
			rpt.setCodStazione(COD_STAZIONE);
			rpt.setCodMsgRichiesta(ccp);
			rpt.setVersione(versione);
			rpt.setStato("RPT_ACCETTATA_NODO");
			rpt.setModelloPagamento(RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP);
			rpt.setXmlRpt(xmlRpt);
			OffsetDateTime adesso = OffsetDateTime.now();
			rpt.setDataMsgRichiesta(adesso);
			rpt.setDataAggiornamentoStato(adesso);
			entityManager.persist(rpt);
			entityManager.flush();
			risultato[0] = rpt;
		});
		return risultato[0];
	}

	private byte[] serializzaPaGetPaymentRes(CtPaymentPA ctPayment) {
		PaGetPaymentRes risposta = new PaGetPaymentRes();
		risposta.setOutcome(StOutcome.OK);
		risposta.setData(ctPayment);
		try {
			JAXBContext jaxbContext = JAXBContext.newInstance(PaGetPaymentRes.class);
			Marshaller marshaller = jaxbContext.createMarshaller();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			marshaller.marshal(new ObjectFactory().createPaGetPaymentRes(risposta), output);
			return output.toByteArray();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}

	private byte[] serializzaPaGetPaymentV2Response(CtPaymentPAV2 ctPayment) {
		PaGetPaymentV2Response risposta = new PaGetPaymentV2Response();
		risposta.setOutcome(StOutcome.OK);
		risposta.setData(ctPayment);
		try {
			JAXBContext jaxbContext = JAXBContext.newInstance(PaGetPaymentV2Response.class);
			Marshaller marshaller = jaxbContext.createMarshaller();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			marshaller.marshal(new ObjectFactory().createPaGetPaymentV2Response(risposta), output);
			return output.toByteArray();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}

	private ResponseEntity<String> invia(String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_XML);
		headers.add("SOAPAction", "paVerifyPaymentNotice");
		headers.add(HEADER_PRINCIPAL, PRINCIPAL_INTERMEDIARIO);
		return restTemplate.postForEntity("http://localhost:" + port + "/ws", new HttpEntity<>(body, headers),
				String.class);
	}

	private String envelope(String idPA, String idBrokerPA, String idStation, String fiscalCode,
			String noticeNumber) {
		// Niente elementFormDefault="qualified" nello schema: solo l'elemento radice
		// (globale) sta nel target namespace, i figli locali (idPA, qrCode, ...) sono
		// unqualified, cioe' senza prefisso/namespace.
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
				                  xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
				  <soapenv:Body>
				    <pafn:paVerifyPaymentNoticeReq>
				      <idPA>%s</idPA>
				      <idBrokerPA>%s</idBrokerPA>
				      <idStation>%s</idStation>
				      <qrCode>
				        <fiscalCode>%s</fiscalCode>
				        <noticeNumber>%s</noticeNumber>
				      </qrCode>
				    </pafn:paVerifyPaymentNoticeReq>
				  </soapenv:Body>
				</soapenv:Envelope>
				""".formatted(idPA, idBrokerPA, idStation, fiscalCode, noticeNumber);
	}

	private String envelopeGetPayment(String idPA, String idBrokerPA, String idStation, String fiscalCode,
			String noticeNumber) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
				                  xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
				  <soapenv:Body>
				    <pafn:paGetPaymentReq>
				      <idPA>%s</idPA>
				      <idBrokerPA>%s</idBrokerPA>
				      <idStation>%s</idStation>
				      <qrCode>
				        <fiscalCode>%s</fiscalCode>
				        <noticeNumber>%s</noticeNumber>
				      </qrCode>
				    </pafn:paGetPaymentReq>
				  </soapenv:Body>
				</soapenv:Envelope>
				""".formatted(idPA, idBrokerPA, idStation, fiscalCode, noticeNumber);
	}

	private String envelopeGetPaymentV2(String idPA, String idBrokerPA, String idStation, String fiscalCode,
			String noticeNumber) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
				                  xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
				  <soapenv:Body>
				    <pafn:paGetPaymentV2Request>
				      <idPA>%s</idPA>
				      <idBrokerPA>%s</idBrokerPA>
				      <idStation>%s</idStation>
				      <qrCode>
				        <fiscalCode>%s</fiscalCode>
				        <noticeNumber>%s</noticeNumber>
				      </qrCode>
				    </pafn:paGetPaymentV2Request>
				  </soapenv:Body>
				</soapenv:Envelope>
				""".formatted(idPA, idBrokerPA, idStation, fiscalCode, noticeNumber);
	}

	@Test
	void probeLivenessEReadinessAccessibiliSenzaAutenticazione() {
		for (String probe : new String[] { "/actuator/health/liveness", "/actuator/health/readiness" }) {
			ResponseEntity<String> risposta = restTemplate.getForEntity("http://localhost:" + port + probe,
					String.class);
			assertThat(risposta.getStatusCode()).as(probe).isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	void richiesteNonSoapRispondonoConErroreDelClient() {
		assertThat(statoPost("{}", MediaType.APPLICATION_JSON)).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(statoPost("<a/>", MediaType.TEXT_XML)).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(statoPost("<soapenv:Envelope", MediaType.TEXT_XML)).isEqualTo(HttpStatus.BAD_REQUEST);

		HttpHeaders headers = new HttpHeaders();
		headers.add(HEADER_PRINCIPAL, PRINCIPAL_INTERMEDIARIO);
		assertThatThrownBy(() -> restTemplate.exchange("http://localhost:" + port + "/ws", HttpMethod.GET,
				new HttpEntity<>(headers), String.class))
				.isInstanceOfSatisfying(HttpClientErrorException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED));
	}

	private HttpStatusCode statoPost(String body, MediaType contentType) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(contentType);
		headers.add(HEADER_PRINCIPAL, PRINCIPAL_INTERMEDIARIO);
		try {
			return restTemplate.postForEntity("http://localhost:" + port + "/ws", new HttpEntity<>(body, headers),
					String.class).getStatusCode();
		}
		catch (HttpStatusCodeException e) {
			return e.getStatusCode();
		}
	}

	@Test
	void wsdlEspostoSullaPathAttesa() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HEADER_PRINCIPAL, PRINCIPAL_INTERMEDIARIO);
		ResponseEntity<String> risposta = restTemplate.exchange("http://localhost:" + port + "/ws/paForNode.wsdl",
				HttpMethod.GET, new HttpEntity<>(headers), String.class);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("paVerifyPaymentNotice");
		// L'indirizzo del servizio nel WSDL e' quello su cui risponde davvero (/ws)
		assertThat(risposta.getBody()).contains("soap:address location=\"http://localhost:" + port + "/ws\"");
	}

	@Test
	void paVerifyPaymentNoticeRestituisceOkPerUnaPendenzaEsistente() {
		IdentificativiPagamento identificativi = creaPendenza("doc-ok-1", 16.00);

		ResponseEntity<String> risposta = invia(
				envelope(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, COD_DOMINIO, identificativi.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>",
				"<fiscalCodePA>" + COD_DOMINIO + "</fiscalCodePA>",
				"<companyName>Comune di Test</companyName>",
				"<amount>16.0</amount>");
	}

	/**
	 * Verifica l'INTERA catena reale (non solo {@code GdeEventFilter} in isolamento, come
	 * {@code GdeEventFilterTest}): {@code OperationIdEndpointInterceptor} valorizza l'attributo
	 * di richiesta dentro {@code MessageDispatcherServlet}, {@code GdeEventFilter} lo legge nel
	 * proprio {@code finally} dopo che anche {@code TransactionIdFilter} ha gia' scritto
	 * l'header di risposta — l'ordine tra i due filtri e' quindi esercitato per davvero, non
	 * assunto. {@code PaForNodeGdeService} e' mockato: non serve un connettore GDE configurato,
	 * interessa solo l'evento passato al mock.
	 */
	@Test
	void eventoGdeDellaCatenaRealePortaLoStessoTransactionIdRestituitoAlClientEIlNomeDellOperazione() throws InterruptedException {
		IdentificativiPagamento identificativi = creaPendenza("doc-gde-1", 16.00);

		ResponseEntity<String> risposta = invia(
				envelope(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, COD_DOMINIO, identificativi.numeroAvviso()));

		String transactionIdRisposta = risposta.getHeaders().getFirst("X-Transaction-ID");
		assertThat(transactionIdRisposta).isNotBlank();

		GdeEventInfo evento = attendiEventoGde(e -> "paVerifyPaymentNotice".equals(e.getTipoEvento()));
		assertThat(evento.getTransactionId()).isEqualTo(transactionIdRisposta);
		assertThat(evento.getPrincipal()).isEqualTo(PRINCIPAL_INTERMEDIARIO);
		assertThat(evento.getStatusCodeRisposta()).isEqualTo(200);
	}

	/**
	 * Un fault applicativo "funzionale" (pendenza sconosciuta, non un errore interno) arriva
	 * comunque con HTTP 200 — verifica che l'evento GDE lo classifichi come KO (non OK, come
	 * accadrebbe classificando dal solo stato HTTP) e riporti codice/descrizione del fault,
	 * con lo stato HTTP reale comunque riportato a parte (200, non alterato).
	 */
	@Test
	void eventoGdeClassificaComeKoUnFaultFunzionaleAncheConHttp200() throws InterruptedException {
		IdentificativiPagamento identificativiNonUsati = IuvUtils.genera(1, "", null, null, 999999L);

		ResponseEntity<String> risposta = invia(envelope(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, COD_DOMINIO,
				identificativiNonUsati.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);

		GdeEventInfo evento = attendiEventoGde(e -> e.getEsito() == EsitoEvento.KO);
		assertThat(evento.getStatusCodeRisposta()).isEqualTo(200);
		assertThat(evento.getDescrizioneEsito()).contains("PAA_PAGAMENTO_SCONOSCIUTO");
	}

	/**
	 * Attende (fino a 2 secondi, poll ogni 20ms) un evento GDE che soddisfi la condizione
	 * data, invece di leggere l'elenco delle invocazioni del mock una volta sola — il body
	 * della risposta HTTP può arrivare al client (quindi al chiamante di {@code invia()})
	 * PRIMA che il {@code finally} di {@code GdeEventFilter} abbia finito di registrare
	 * l'evento sul mock: leggere l'elenco una volta sola, subito dopo {@code invia()}, è una
	 * corsa — osservata realmente su CI (riproducibile a intermittenza, non su ogni
	 * esecuzione) anche quando localmente non si manifestava.
	 * <p>
	 * Usa {@code Mockito.mockingDetails(...).getInvocations()}, non {@code Mockito.verify}:
	 * quest'ultimo, con {@code atLeastOnce()}, LANCIA subito se le invocazioni sono zero
	 * (non le "non trova e basta") — col mock ancora a zero interazioni (il caso più comune
	 * a inizio polling) il loop non arriverebbe mai al retry.
	 */
	private GdeEventInfo attendiEventoGde(java.util.function.Predicate<GdeEventInfo> condizione)
			throws InterruptedException {
		long scadenza = System.currentTimeMillis() + 2000;
		List<GdeEventInfo> ultimiVisti = List.of();
		while (System.currentTimeMillis() < scadenza) {
			ultimiVisti = Mockito.mockingDetails(paForNodeGdeService).getInvocations().stream()
					.map(inv -> (GdeEventInfo) inv.getArgument(0))
					.toList();
			Optional<GdeEventInfo> trovato = ultimiVisti.stream().filter(condizione).findFirst();
			if (trovato.isPresent()) {
				return trovato.get();
			}
			Thread.sleep(20);
		}
		throw new AssertionError(
				"Nessun evento GDE soddisfa la condizione entro il timeout. Eventi visti: " + ultimiVisti);
	}

	@Test
	void paVerifyPaymentNoticeRestituisceFaultPerIntermediarioSconosciuto() {
		ResponseEntity<String> risposta = invia(
				envelope(COD_DOMINIO, "BROKER-INESISTENTE", COD_STAZIONE, COD_DOMINIO, "100000000000000001"));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>",
				"<faultCode>PAA_ID_INTERMEDIARIO_ERRATO</faultCode>");
	}

	@Test
	void paVerifyPaymentNoticeRestituisceFaultPerPendenzaSconosciuta() {
		// Un numeroAvviso formalmente valido (check digit corretto per AuxDigit 1) ma mai
		// assegnato a nessuna pendenza: deve arrivare alla ricerca ed esaurirsi li', non
		// fallire prima sulla validazione del formato.
		IdentificativiPagamento identificativiNonUsati = IuvUtils.genera(1, "", null, null, 999999L);

		ResponseEntity<String> risposta = invia(envelope(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, COD_DOMINIO,
				identificativiNonUsati.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<faultCode>PAA_PAGAMENTO_SCONOSCIUTO</faultCode>");
	}

	/**
	 * Verifica richiesta esplicitamente dall'issue #6: nessuna riga {@code rpt} dopo
	 * l'attivazione — l'operazione e' diventata una sola lettura della pendenza.
	 */
	@Test
	void paGetPaymentRestituisceIDatiDelPagamentoSenzaScrivereRpt() {
		IdentificativiPagamento identificativi = creaPendenza("doc-getpayment-1", 16.00);

		ResponseEntity<String> risposta = invia(envelopeGetPayment(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				COD_DOMINIO, identificativi.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>",
				"<creditorReferenceId>" + identificativi.iuv() + "</creditorReferenceId>",
				"<companyName>Comune di Test</companyName>");
		assertThat(jdbcTemplate.queryForObject("select count(*) from rpt where iuv = ?", Integer.class,
				identificativi.iuv())).isZero();
	}

	@Test
	void paGetPaymentRestituisceFaultPerPendenzaSconosciuta() {
		IdentificativiPagamento identificativiNonUsati = IuvUtils.genera(1, "", null, null, 999998L);

		ResponseEntity<String> risposta = invia(envelopeGetPayment(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				COD_DOMINIO, identificativiNonUsati.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>",
				"<faultCode>PAA_PAGAMENTO_SCONOSCIUTO</faultCode>");
	}

	@Test
	void paGetPaymentRileggeLoStatoCambiatoPrimaDelLock() {
		IdentificativiPagamento identificativi = creaPendenza("doc-getpayment-race", 16.00);
		// Simula un aggiornamento confermato da un'altra transazione dopo la prima
		// lettura: l'entita' gia' presente nel persistence context rimane NON_ESEGUITO.
		Mockito.doAnswer(invocation -> {
			var risultato = entityManager.createQuery(
					"select p from Pendenza p where p.idDominio = :dominio and p.iuv = :iuv",
					Pendenza.class)
					.setParameter("dominio", idDominio).setParameter("iuv", identificativi.iuv())
					.getResultStream().findFirst();
			TransactionTemplate altraTransazione = new TransactionTemplate(transactionManager);
			altraTransazione.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			altraTransazione.executeWithoutResult(status -> {
				int aggiornate = jdbcTemplate.update(
						"update versamenti set stato_versamento = 'ESEGUITO' where iuv_versamento = ?",
						identificativi.iuv());
				assertThat(aggiornate).isEqualTo(1);
			});
			return risultato;
		}).when(pendenzaRepository).findByIdDominioAndIuv(idDominio, identificativi.iuv());

		ResponseEntity<String> risposta = invia(envelopeGetPayment(COD_DOMINIO, COD_INTERMEDIARIO,
				COD_STAZIONE, COD_DOMINIO, identificativi.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>",
				"<faultCode>PAA_PAGAMENTO_DUPLICATO</faultCode>");
		assertThat(jdbcTemplate.queryForObject("select count(*) from rpt where iuv = ?",
				Integer.class, identificativi.iuv())).isZero();
	}

	private String envelopeSendRT(String idPA, String idBrokerPA, String idStation, String receiptId,
			String fiscalCode, String creditorReferenceId, double paymentAmount, int idTransfer,
			double transferAmount) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
				                  xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
				  <soapenv:Body>
				    <pafn:paSendRTReq>
				      <idPA>%s</idPA>
				      <idBrokerPA>%s</idBrokerPA>
				      <idStation>%s</idStation>
				      <receipt>
				        <receiptId>%s</receiptId>
				        <noticeNumber>300000000000000001</noticeNumber>
				        <fiscalCode>%s</fiscalCode>
				        <outcome>OK</outcome>
				        <creditorReferenceId>%s</creditorReferenceId>
				        <paymentAmount>%s</paymentAmount>
				        <description>test</description>
				        <companyName>Comune di Test</companyName>
				        <debtor>
				          <uniqueIdentifier>
				            <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
				            <entityUniqueIdentifierValue>RSSMRA80A01H501U</entityUniqueIdentifierValue>
				          </uniqueIdentifier>
				          <fullName>Mario Rossi</fullName>
				        </debtor>
				        <transferList>
				          <transfer>
				            <idTransfer>%s</idTransfer>
				            <transferAmount>%s</transferAmount>
				            <fiscalCodePA>%s</fiscalCodePA>
				            <IBAN>IT60X0542811101000000123456</IBAN>
				            <remittanceInformation>test</remittanceInformation>
				            <transferCategory>9/0101108TS/</transferCategory>
				          </transfer>
				        </transferList>
				        <idPSP>PSP1</idPSP>
				        <PSPCompanyName>Psp Uno</PSPCompanyName>
				        <idChannel>CH1</idChannel>
				        <channelDescription>canale</channelDescription>
				      </receipt>
				    </pafn:paSendRTReq>
				  </soapenv:Body>
				</soapenv:Envelope>
				""".formatted(idPA, idBrokerPA, idStation, receiptId, fiscalCode, creditorReferenceId, paymentAmount,
				idTransfer, transferAmount, fiscalCode);
	}

	@Test
	void paSendRTAcquisisceLaRicevutaDopoLattivazione() {
		IdentificativiPagamento identificativi = creaPendenza("doc-sendrt-1", 16.00);
		// RPT con xmlRpt, come l'avrebbe lasciata il vecchio core prima del passaggio alla
		// 3.11 — questo modulo non ne scrive piu' (issue #6), vedi Javadoc del metodo.
		rptAttivataDalVecchioCoreFixture(identificativi, "legacy-ccp-" + identificativi.iuv(),
				RptAttivazioneService.VERSIONE_SANP_240);

		ResponseEntity<String> risposta = invia(envelopeSendRT(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				"receipt-" + identificativi.iuv(), COD_DOMINIO, identificativi.iuv(), 16.00, 1, 16.00));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>");
		assertThat(jdbcTemplate.queryForObject("select stato from rpt where iuv = ?", String.class,
				identificativi.iuv())).isEqualTo("RT_ACCETTATA_PA");
		assertThat(jdbcTemplate.queryForObject("select stato_versamento from versamenti where iuv_versamento = ?",
				String.class, identificativi.iuv())).isEqualTo("ESEGUITO");
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from pagamenti where iuv = ? and iur = ?", Integer.class, identificativi.iuv(),
				"receipt-" + identificativi.iuv())).isEqualTo(1);
	}

	/**
	 * Verifica che il rifiuto della validazione semantica venga persistito ({@code
	 * RT_RIFIUTATA_PA}, {@code xmlRt} conservato) PRIMA del fault, non scartato col
	 * rollback della transazione — stesso comportamento del legacy ({@code
	 * CtReceiptUtils.acquisisciRT}, commit separato prima di rilanciare {@code
	 * PAA_SEMANTICA}). Il controllo fatale innescato qui e' il numero di trasferimenti
	 * (2 nella ricevuta, contro 1 solo nella richiesta originale) — {@code
	 * creditorReferenceId} non e' costruibile in un mismatch realistico via contratto
	 * pubblico, dato che e' anche la chiave con cui si trova la pendenza stessa.
	 */
	@Test
	void paSendRTPersisteIlRifiutoDellaValidazioneSemanticaPrimaDelFault() {
		IdentificativiPagamento identificativi = creaPendenza("doc-semantica-rifiuto", 16.00);
		rptAttivataDalVecchioCoreFixture(identificativi, "legacy-ccp-" + identificativi.iuv(),
				RptAttivazioneService.VERSIONE_SANP_240);

		String receiptId = "receipt-" + identificativi.iuv();
		String envelopeConDueTrasferimenti = """
				<?xml version="1.0" encoding="UTF-8"?>
				<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
				                  xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
				  <soapenv:Body>
				    <pafn:paSendRTReq>
				      <idPA>%1$s</idPA>
				      <idBrokerPA>%2$s</idBrokerPA>
				      <idStation>%3$s</idStation>
				      <receipt>
				        <receiptId>%4$s</receiptId>
				        <noticeNumber>300000000000000001</noticeNumber>
				        <fiscalCode>%1$s</fiscalCode>
				        <outcome>OK</outcome>
				        <creditorReferenceId>%5$s</creditorReferenceId>
				        <paymentAmount>16.00</paymentAmount>
				        <description>test</description>
				        <companyName>Comune di Test</companyName>
				        <debtor>
				          <uniqueIdentifier>
				            <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
				            <entityUniqueIdentifierValue>RSSMRA80A01H501U</entityUniqueIdentifierValue>
				          </uniqueIdentifier>
				          <fullName>Mario Rossi</fullName>
				        </debtor>
				        <transferList>
				          <transfer>
				            <idTransfer>1</idTransfer>
				            <transferAmount>8.00</transferAmount>
				            <fiscalCodePA>%1$s</fiscalCodePA>
				            <IBAN>IT60X0542811101000000123456</IBAN>
				            <remittanceInformation>test</remittanceInformation>
				            <transferCategory>9/0101108TS/</transferCategory>
				          </transfer>
				          <transfer>
				            <idTransfer>2</idTransfer>
				            <transferAmount>8.00</transferAmount>
				            <fiscalCodePA>%1$s</fiscalCodePA>
				            <IBAN>IT60X0542811101000000123456</IBAN>
				            <remittanceInformation>test</remittanceInformation>
				            <transferCategory>9/0101108TS/</transferCategory>
				          </transfer>
				        </transferList>
				        <idPSP>PSP1</idPSP>
				        <PSPCompanyName>Psp Uno</PSPCompanyName>
				        <idChannel>CH1</idChannel>
				        <channelDescription>canale</channelDescription>
				      </receipt>
				    </pafn:paSendRTReq>
				  </soapenv:Body>
				</soapenv:Envelope>
				""".formatted(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, receiptId, identificativi.iuv());

		ResponseEntity<String> risposta = invia(envelopeConDueTrasferimenti);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_SEMANTICA</faultCode>");
		assertThat(jdbcTemplate.queryForObject("select stato from rpt where iuv = ?", String.class,
				identificativi.iuv())).isEqualTo("RT_RIFIUTATA_PA");
		assertThat(jdbcTemplate.queryForObject("select xml_rt is not null from rpt where iuv = ?", Boolean.class,
				identificativi.iuv())).isTrue();
	}

	@Test
	void paSendRTRestituisceFaultPerRptSconosciuta() {
		IdentificativiPagamento identificativiNonUsati = IuvUtils.genera(1, "", null, null, 999996L);

		ResponseEntity<String> risposta = invia(envelopeSendRT(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				"receipt-sconosciuta", COD_DOMINIO, identificativiNonUsati.iuv(), 16.00, 1, 16.00));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_RPT_SCONOSCIUTA</faultCode>");
	}

	/**
	 * Una RPT preesistente di un ALTRO modello di pagamento (non {@code ATTIVATO_PRESSO_PSP},
	 * qui simulato con {@code IMMEDIATO}, codifica legacy "0") non deve bloccare la
	 * ricostruzione "standin": nessuna attivazione (nessuna RPT del modello PSP) e' mai
	 * avvenuta per questa pendenza, stesso filtro per modello del legacy
	 * ({@code rptBD.getRpt(..., ModelloPagamento.ATTIVATO_PRESSO_PSP, null)}).
	 */
	@Test
	void paSendRTRicostruisceStandinAncheConUnaRptPreesistenteDiAltroModello() {
		IdentificativiPagamento identificativi = creaPendenza("doc-standin-altro-modello", 16.00);
		Pendenza pendenza = pendenzaRepository.findByIdDominioAndIuv(idDominio, identificativi.iuv()).orElseThrow();

		Rpt altroModello = new Rpt();
		altroModello.setIdVersamento(pendenza.getId());
		altroModello.setIuv(identificativi.iuv());
		altroModello.setIur("altro-" + identificativi.iuv());
		altroModello.setCodDominio(COD_DOMINIO);
		altroModello.setCodStazione(COD_STAZIONE);
		altroModello.setCodMsgRichiesta("msg-altro-" + identificativi.iuv());
		altroModello.setStato("RPT_RIFIUTATA_NODO");
		altroModello.setVersione("SANP_240");
		altroModello.setModelloPagamento("0");
		OffsetDateTime adesso = OffsetDateTime.now();
		altroModello.setDataMsgRichiesta(adesso);
		altroModello.setDataAggiornamentoStato(adesso);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			entityManager.persist(altroModello);
			entityManager.flush();
		});

		ResponseEntity<String> risposta = invia(envelopeSendRT(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				"receipt-" + identificativi.iuv(), COD_DOMINIO, identificativi.iuv(), 16.00, 1, 16.00));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>");
		assertThat(jdbcTemplate.queryForObject("select stato from rpt where iuv = ? and ccp = ?", String.class,
				identificativi.iuv(), "receipt-" + identificativi.iuv())).isEqualTo("RT_ACCETTATA_PA");
	}

	/**
	 * Come {@link #paSendRTRicostruisceStandinAncheConUnaRptPreesistenteDiAltroModello}, ma
	 * con la RPT di altro modello effettivamente nello stato {@code RPT_ACCETTATA_NODO} (non
	 * solo in un altro stato generico): senza il filtro per modello anche sulla ricerca della
	 * RPT in attesa, questa riga verrebbe selezionata ed usata al posto della ricostruzione
	 * "standin" — la ricevuta finirebbe agganciata alla RPT sbagliata (altro canale di
	 * pagamento) invece di generarne una nuova.
	 */
	@Test
	void paSendRTRicostruisceStandinAncheConUnaRptDiAltroModelloGiaAccettataDalNodo() {
		IdentificativiPagamento identificativi = creaPendenza("doc-standin-altro-accettata", 16.00);
		Pendenza pendenza = pendenzaRepository.findByIdDominioAndIuv(idDominio, identificativi.iuv()).orElseThrow();

		Rpt altroModello = new Rpt();
		altroModello.setIdVersamento(pendenza.getId());
		altroModello.setIuv(identificativi.iuv());
		altroModello.setIur("altro-" + identificativi.iuv());
		altroModello.setCodDominio(COD_DOMINIO);
		altroModello.setCodStazione(COD_STAZIONE);
		altroModello.setCodMsgRichiesta("msg-altro-" + identificativi.iuv());
		altroModello.setStato("RPT_ACCETTATA_NODO");
		altroModello.setVersione("SANP_240");
		altroModello.setModelloPagamento("0");
		OffsetDateTime adesso = OffsetDateTime.now();
		altroModello.setDataMsgRichiesta(adesso);
		altroModello.setDataAggiornamentoStato(adesso);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			entityManager.persist(altroModello);
			entityManager.flush();
		});

		ResponseEntity<String> risposta = invia(envelopeSendRT(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				"receipt-" + identificativi.iuv(), COD_DOMINIO, identificativi.iuv(), 16.00, 1, 16.00));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>");
		// La ricevuta deve aver creato una RPT NUOVA (standin), non essersi agganciata alla
		// riga di altro modello: quella resta intatta, ancora RPT_ACCETTATA_NODO.
		assertThat(jdbcTemplate.queryForObject("select stato from rpt where iuv = ? and ccp = ?", String.class,
				identificativi.iuv(), "receipt-" + identificativi.iuv())).isEqualTo("RT_ACCETTATA_PA");
		assertThat(jdbcTemplate.queryForObject("select stato from rpt where iuv = ? and ccp = ?", String.class,
				identificativi.iuv(), "altro-" + identificativi.iuv())).isEqualTo("RPT_ACCETTATA_NODO");
		assertThat(jdbcTemplate.queryForObject("select count(*) from rpt where iuv = ?", Integer.class,
				identificativi.iuv())).isEqualTo(2);
	}

	private String envelopeSendRTV2(String idPA, String idBrokerPA, String idStation, String receiptId,
			String fiscalCode, String creditorReferenceId, double paymentAmount, int idTransfer,
			double transferAmount) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
				                  xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
				  <soapenv:Body>
				    <pafn:paSendRTV2Request>
				      <idPA>%s</idPA>
				      <idBrokerPA>%s</idBrokerPA>
				      <idStation>%s</idStation>
				      <receipt>
				        <receiptId>%s</receiptId>
				        <noticeNumber>300000000000000001</noticeNumber>
				        <fiscalCode>%s</fiscalCode>
				        <outcome>OK</outcome>
				        <creditorReferenceId>%s</creditorReferenceId>
				        <paymentAmount>%s</paymentAmount>
				        <description>test</description>
				        <companyName>Comune di Test</companyName>
				        <debtor>
				          <uniqueIdentifier>
				            <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
				            <entityUniqueIdentifierValue>RSSMRA80A01H501U</entityUniqueIdentifierValue>
				          </uniqueIdentifier>
				          <fullName>Mario Rossi</fullName>
				        </debtor>
				        <transferList>
				          <transfer>
				            <idTransfer>%s</idTransfer>
				            <transferAmount>%s</transferAmount>
				            <fiscalCodePA>%s</fiscalCodePA>
				            <IBAN>IT60X0542811101000000123456</IBAN>
				            <remittanceInformation>test</remittanceInformation>
				            <transferCategory>9/0101108TS/</transferCategory>
				          </transfer>
				        </transferList>
				        <idPSP>PSP1</idPSP>
				        <PSPCompanyName>Psp Uno</PSPCompanyName>
				        <idChannel>CH1</idChannel>
				        <channelDescription>canale</channelDescription>
				      </receipt>
				    </pafn:paSendRTV2Request>
				  </soapenv:Body>
				</soapenv:Envelope>
				""".formatted(idPA, idBrokerPA, idStation, receiptId, fiscalCode, creditorReferenceId, paymentAmount,
				idTransfer, transferAmount, fiscalCode);
	}

	/**
	 * Asimmetria col caso V1 ({@link
	 * #paSendRTRicostruisceStandinAncheConUnaRptDiAltroModelloGiaAccettataDalNodo}): su
	 * {@code paSendRTV2} una RPT di un ALTRO modello BLOCCA lo standin — il legacy qui non
	 * filtra per modello. Stato diverso da {@code RPT_ACCETTATA_NODO} apposta: altrimenti
	 * la ricerca (anch'essa non filtrata per modello su V2) la troverebbe e l'userebbe
	 * direttamente come RPT in attesa, senza mai arrivare al controllo dello standin.
	 */
	@Test
	void paSendRTV2RestituisceFaultSeEsisteGiaUnaRptDiAltroModello() {
		IdentificativiPagamento identificativi = creaPendenza("doc-standin-v2-altro-modello", 16.00);
		Pendenza pendenza = pendenzaRepository.findByIdDominioAndIuv(idDominio, identificativi.iuv()).orElseThrow();

		Rpt altroModello = new Rpt();
		altroModello.setIdVersamento(pendenza.getId());
		altroModello.setIuv(identificativi.iuv());
		altroModello.setIur("altro-" + identificativi.iuv());
		altroModello.setCodDominio(COD_DOMINIO);
		altroModello.setCodStazione(COD_STAZIONE);
		altroModello.setCodMsgRichiesta("msg-altro-" + identificativi.iuv());
		altroModello.setStato("RPT_RIFIUTATA_NODO");
		altroModello.setVersione("SANP_240");
		altroModello.setModelloPagamento("0");
		OffsetDateTime adesso = OffsetDateTime.now();
		altroModello.setDataMsgRichiesta(adesso);
		altroModello.setDataAggiornamentoStato(adesso);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			entityManager.persist(altroModello);
			entityManager.flush();
		});

		ResponseEntity<String> risposta = invia(envelopeSendRTV2(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				"receipt-" + identificativi.iuv(), COD_DOMINIO, identificativi.iuv(), 16.00, 1, 16.00));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_RPT_SCONOSCIUTA</faultCode>");
		assertThat(jdbcTemplate.queryForObject("select count(*) from rpt where iuv = ?", Integer.class,
				identificativi.iuv())).isEqualTo(1);
	}

	/**
	 * Percorso a lieto fine completo di {@code paSendRTV2} su una RPT con {@code xmlRpt}
	 * (simulata come dal vecchio core prima del passaggio alla 3.11, vedi Javadoc di
	 * {@link #rptAttivataDalVecchioCoreFixture}): a differenza dei test di standin sopra, la
	 * validazione semantica REALE ({@code validaSemanticaV2}, incluso il controllo su
	 * {@code UniqueIdentifierDebtor} presente solo su V2) viene davvero esercitata.
	 */
	@Test
	void paSendRTV2AcquisisceLaRicevutaDopoLattivazione() {
		IdentificativiPagamento identificativi = creaPendenza("doc-sendrtv2-1", 16.00);
		rptAttivataDalVecchioCoreFixture(identificativi, "legacy-ccp-" + identificativi.iuv(),
				RptAttivazioneService.VERSIONE_SANP_321_V2);

		ResponseEntity<String> risposta = invia(envelopeSendRTV2(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				"receipt-" + identificativi.iuv(), COD_DOMINIO, identificativi.iuv(), 16.00, 1, 16.00));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>");
		assertThat(jdbcTemplate.queryForObject("select stato from rpt where iuv = ?", String.class,
				identificativi.iuv())).isEqualTo("RT_ACCETTATA_PA");
	}

	/**
	 * Caso ibrido (punto #17 del design doc, confermato scenario reale da pagoPA): RPT
	 * attivata con {@code paGetPaymentV2} ma ricevuta arrivata tramite {@code paSendRT}
	 * (V1) — la validazione semantica deve passare per il ramo V2 ({@code
	 * PaSendRTService#validaSemanticaIbridaV2RptV1Rt}), non quello V1, e la RPT va marcata
	 * {@code RPTV2_RTV1}.
	 */
	@Test
	void paSendRTAcquisisceLaRicevutaDopoAttivazioneV2CasoIbrido() {
		IdentificativiPagamento identificativi = creaPendenza("doc-ibrido-v2rtv1", 16.00);
		rptAttivataDalVecchioCoreFixture(identificativi, "legacy-ccp-" + identificativi.iuv(),
				RptAttivazioneService.VERSIONE_SANP_321_V2);

		ResponseEntity<String> risposta = invia(envelopeSendRT(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				"receipt-" + identificativi.iuv(), COD_DOMINIO, identificativi.iuv(), 16.00, 1, 16.00));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>");
		assertThat(jdbcTemplate.queryForObject("select stato from rpt where iuv = ?", String.class,
				identificativi.iuv())).isEqualTo("RT_ACCETTATA_PA");
		assertThat(jdbcTemplate.queryForObject("select versione from rpt where iuv = ?", String.class,
				identificativi.iuv())).isEqualTo("RPTV2_RTV1");
	}

	/**
	 * Caso simmetrico (punto #18): RPT attivata con {@code paGetPayment} (V1) ma ricevuta
	 * arrivata tramite {@code paSendRTV2} — validazione nel ramo {@code
	 * validaSemanticaIbridaV1RptV2Rt}, RPT marcata {@code RPTV1_RTV2}. Nessun ramo SANP_230
	 * (punto #19, non raggiungibile per costruzione in questo modulo).
	 */
	@Test
	void paSendRTV2AcquisisceLaRicevutaDopoAttivazioneV1CasoIbrido() {
		IdentificativiPagamento identificativi = creaPendenza("doc-ibrido-v1rtv2", 16.00);
		rptAttivataDalVecchioCoreFixture(identificativi, "legacy-ccp-" + identificativi.iuv(),
				RptAttivazioneService.VERSIONE_SANP_240);

		ResponseEntity<String> risposta = invia(envelopeSendRTV2(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				"receipt-" + identificativi.iuv(), COD_DOMINIO, identificativi.iuv(), 16.00, 1, 16.00));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>");
		assertThat(jdbcTemplate.queryForObject("select stato from rpt where iuv = ?", String.class,
				identificativi.iuv())).isEqualTo("RT_ACCETTATA_PA");
		assertThat(jdbcTemplate.queryForObject("select versione from rpt where iuv = ?", String.class,
				identificativi.iuv())).isEqualTo("RPTV1_RTV2");
	}

	/**
	 * {@code UniqueIdentifierDebtor} e' fatale solo su V2 (il legacy non confronta il
	 * debitore affatto su V1, vedi Javadoc di {@code PaSendRTService#validaSemanticaV2}).
	 */
	@Test
	void paSendRTV2RestituisceFaultSeLidentificativoDelDebitoreNonCorrisponde() {
		IdentificativiPagamento identificativi = creaPendenza("doc-sendrtv2-debitore", 16.00);
		rptAttivataDalVecchioCoreFixture(identificativi, "legacy-ccp-" + identificativi.iuv(),
				RptAttivazioneService.VERSIONE_SANP_321_V2);

		String receiptId = "receipt-" + identificativi.iuv();
		String envelopeConDebitoreDiverso = """
				<?xml version="1.0" encoding="UTF-8"?>
				<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
				                  xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
				  <soapenv:Body>
				    <pafn:paSendRTV2Request>
				      <idPA>%1$s</idPA>
				      <idBrokerPA>%2$s</idBrokerPA>
				      <idStation>%3$s</idStation>
				      <receipt>
				        <receiptId>%4$s</receiptId>
				        <noticeNumber>300000000000000001</noticeNumber>
				        <fiscalCode>%1$s</fiscalCode>
				        <outcome>OK</outcome>
				        <creditorReferenceId>%5$s</creditorReferenceId>
				        <paymentAmount>16.00</paymentAmount>
				        <description>test</description>
				        <companyName>Comune di Test</companyName>
				        <debtor>
				          <uniqueIdentifier>
				            <entityUniqueIdentifierType>F</entityUniqueIdentifierType>
				            <entityUniqueIdentifierValue>VRDGPP85T10H501X</entityUniqueIdentifierValue>
				          </uniqueIdentifier>
				          <fullName>Altra Persona</fullName>
				        </debtor>
				        <transferList>
				          <transfer>
				            <idTransfer>1</idTransfer>
				            <transferAmount>16.00</transferAmount>
				            <fiscalCodePA>%1$s</fiscalCodePA>
				            <IBAN>IT60X0542811101000000123456</IBAN>
				            <remittanceInformation>test</remittanceInformation>
				            <transferCategory>9/0101108TS/</transferCategory>
				          </transfer>
				        </transferList>
				        <idPSP>PSP1</idPSP>
				        <PSPCompanyName>Psp Uno</PSPCompanyName>
				        <idChannel>CH1</idChannel>
				        <channelDescription>canale</channelDescription>
				      </receipt>
				    </pafn:paSendRTV2Request>
				  </soapenv:Body>
				</soapenv:Envelope>
				""".formatted(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, receiptId, identificativi.iuv());

		ResponseEntity<String> risposta = invia(envelopeConDebitoreDiverso);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_SEMANTICA</faultCode>");
		assertThat(jdbcTemplate.queryForObject("select stato from rpt where iuv = ?", String.class,
				identificativi.iuv())).isEqualTo("RT_RIFIUTATA_PA");
	}

	@Test
	void paGetPaymentV2RestituisceIDatiDelPagamentoSenzaScrivereRpt() {
		IdentificativiPagamento identificativi = creaPendenza("doc-getpaymentv2-1", 16.00);

		ResponseEntity<String> risposta = invia(envelopeGetPaymentV2(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				COD_DOMINIO, identificativi.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>",
				"<creditorReferenceId>" + identificativi.iuv() + "</creditorReferenceId>",
				"<companyName>Comune di Test</companyName>");
		assertThat(jdbcTemplate.queryForObject("select count(*) from rpt where iuv = ?", Integer.class,
				identificativi.iuv())).isZero();
	}

	@Test
	void paGetPaymentV2RestituisceFaultPerPendenzaSconosciuta() {
		IdentificativiPagamento identificativiNonUsati = IuvUtils.genera(1, "", null, null, 999997L);

		ResponseEntity<String> risposta = invia(envelopeGetPaymentV2(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				COD_DOMINIO, identificativiNonUsati.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>",
				"<faultCode>PAA_PAGAMENTO_SCONOSCIUTO</faultCode>");
	}

}
