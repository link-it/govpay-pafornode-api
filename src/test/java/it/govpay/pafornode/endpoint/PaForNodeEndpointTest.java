package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

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

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.entity.TipoContabilita;
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
	private PosizioneDebitoriaService posizioneDebitoriaService;

	private Long idDominio;

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
	}

	@AfterEach
	void pulisci() {
		stazioneRepository.deleteAll();
		intermediarioRepository.deleteAll();
		dominioRepository.deleteAll();
	}

	private IdentificativiPagamento creaPendenza(String codDocumento, double importo) {
		IdentificativiPagamento identificativi = IuvUtils.genera(1, "", null, null,
				System.nanoTime() % 1_000_000_000_000_00L);

		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setIdApplicazione(1L);
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
		pendenza.setIdApplicazione(1L);
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
}
