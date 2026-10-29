package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.Mockito;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import it.govpay.pendenze.entity.Pendenza;
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
@Import(PaForNodeEndpointTest.ClockFissoConfig.class)
class PaForNodeEndpointTest {

	/**
	 * Clock fisso: serve solo al test di collisione sulla chiave della RPT (due attivazioni
	 * con lo stesso {@code ccp}, derivato da {@code clock.millis()}) — innocuo per gli altri
	 * test di questa classe, nessuno dipende da un orologio che avanza davvero.
	 */
	@TestConfiguration
	static class ClockFissoConfig {
		// Nome diverso da "clock": Application.clock() resta registrato, @Primary sceglie
		// comunque questo bean per l'autowiring per tipo — un secondo bean con lo stesso nome
		// farebbe fallire il contesto (BeanDefinitionOverrideException, non consentito di
		// default).
		@Bean
		@Primary
		Clock clockFisso() {
			return Clock.fixed(Instant.now(), ZoneId.systemDefault());
		}
	}

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

		stazioneRepository.save(StazioneEntity.builder()
				.codStazione(COD_STAZIONE)
				.abilitato(true)
				.applicationCode(1)
				.intermediario(intermediario)
				.build());

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
		stazioneRepository.deleteAll();
		intermediarioRepository.deleteAll();
		dominioRepository.deleteAll();
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
	void wsdlEspostoSullaPathAttesa() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HEADER_PRINCIPAL, PRINCIPAL_INTERMEDIARIO);
		ResponseEntity<String> risposta = restTemplate.exchange("http://localhost:" + port + "/ws/paForNode.wsdl",
				HttpMethod.GET, new HttpEntity<>(headers), String.class);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("paVerifyPaymentNotice");
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

	@Test
	void paGetPaymentAttivaLaRptERestituisceIDatiDelPagamento() {
		IdentificativiPagamento identificativi = creaPendenza("doc-getpayment-1", 16.00);

		ResponseEntity<String> risposta = invia(envelopeGetPayment(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				COD_DOMINIO, identificativi.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>",
				"<creditorReferenceId>" + identificativi.iuv() + "</creditorReferenceId>",
				"<companyName>Comune di Test</companyName>");
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
	void paGetPaymentSuCollisioneDiChiaveRptRestituisceFaultInveceDiPropagareLerrore() {
		// Clock fisso (ClockFissoConfig): le due chiamate derivano lo stesso ccp
		// (System.millis() congelato), quindi la seconda attivazione collide sulla chiave
		// fisica (iuv, ccp, cod_dominio) con la RPT appena creata dalla prima — anche se
		// l'annullamento delle RPT pendenti la marca RPT_ANNULLATA, non le libera la chiave.
		// Prima della correzione, il commit (fuori dal try/catch perché @Transactional stava
		// sull'endpoint) faceva arrivare l'errore SQL grezzo al chiamante invece della
		// risposta applicativa KO/PAA_SYSTEM_ERROR.
		IdentificativiPagamento identificativi = creaPendenza("doc-getpayment-collisione", 16.00);
		String richiesta = envelopeGetPayment(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, COD_DOMINIO,
				identificativi.numeroAvviso());

		ResponseEntity<String> prima = invia(richiesta);
		assertThat(prima.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(prima.getBody()).contains("<outcome>OK</outcome>");

		ResponseEntity<String> seconda = invia(richiesta);

		assertThat(seconda.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(seconda.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_SYSTEM_ERROR</faultCode>");
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

		ResponseEntity<String> attivazione = invia(envelopeGetPayment(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				COD_DOMINIO, identificativi.numeroAvviso()));
		assertThat(attivazione.getBody()).contains("<outcome>OK</outcome>");

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

	@Test
	void paSendRTRestituisceFaultPerRptSconosciuta() {
		IdentificativiPagamento identificativiNonUsati = IuvUtils.genera(1, "", null, null, 999996L);

		ResponseEntity<String> risposta = invia(envelopeSendRT(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				"receipt-sconosciuta", COD_DOMINIO, identificativiNonUsati.iuv(), 16.00, 1, 16.00));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_RPT_SCONOSCIUTA</faultCode>");
	}

	@Test
	void paGetPaymentV2AttivaLaRptERestituisceIDatiDelPagamento() {
		IdentificativiPagamento identificativi = creaPendenza("doc-getpaymentv2-1", 16.00);

		ResponseEntity<String> risposta = invia(envelopeGetPaymentV2(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE,
				COD_DOMINIO, identificativi.numeroAvviso()));

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>",
				"<creditorReferenceId>" + identificativi.iuv() + "</creditorReferenceId>",
				"<companyName>Comune di Test</companyName>");
		assertThat(jdbcTemplate.queryForObject("select versione from rpt where iuv = ?", String.class,
				identificativi.iuv())).isEqualTo("SANP_321_V2");
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
