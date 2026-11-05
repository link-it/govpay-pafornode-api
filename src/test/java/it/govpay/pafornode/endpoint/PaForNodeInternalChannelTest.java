package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import it.govpay.common.auth.GovpayPasswordEncoder;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.entity.TipoContabilita;
import it.govpay.common.gde.GdeEventInfo;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.common.utils.IuvUtils;
import it.govpay.common.utils.IuvUtils.IdentificativiPagamento;
import it.govpay.pafornode.gde.PaForNodeGdeService;
import it.govpay.pafornode.security.UtenzaEntity;
import it.govpay.pafornode.security.UtenzaRepository;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * Issue #10: verifica di integrazione (contesto Spring completo, richiesta SOAP reale via
 * HTTP) del canale interno {@code /internal/PagamentiTelematiciCCPservice} — stesso
 * servlet/WSDL/endpoint del canale Nodo ({@link PaForNodeEndpointTest}, che resta su
 * {@code /ws}), ma autenticato con
 * {@code AuthType.BASIC} contro un'utenza applicativa invece che contro l'intermediario
 * pagoPA. Tre cose distintive da esercitare per davvero, non solo a livello di unit test:
 * <ul>
 * <li>l'autenticazione BASIC funziona end-to-end sullo stesso servlet del canale Nodo;</li>
 * <li>il confronto principal-autenticato/intermediario ({@code
 * PaForNodeChecks#verificaPrincipalAutenticato}) e' bypassato su questo canale — l'utenza
 * applicativa non ha nulla a che fare con l'intermediario dichiarato nel body;</li>
 * <li>l'header {@code GOVPAY-ON-BEHALF-OF} viene registrato nell'evento GDE come operatore
 * richiedente, distinto dal principal tecnico.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaForNodeInternalChannelTest {

	private static final String COD_DOMINIO = "22233344455";
	private static final String COD_INTERMEDIARIO = "BROKER-INTERNO-TEST";
	private static final String COD_STAZIONE = "STAZIONE-INTERNO-TEST";
	private static final String PRINCIPAL_INTERMEDIARIO = "CN=non-dovrebbe-contare.example.it";
	private static final String PRINCIPAL_UTENZA = "app-rt-batch-test";
	private static final String PASSWORD_UTENZA = "password-di-test";
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
	private UtenzaRepository utenzaRepository;

	@Autowired
	private GovpayPasswordEncoder passwordEncoder;

	@Autowired
	private PosizioneDebitoriaService posizioneDebitoriaService;

	@MockitoBean
	private PaForNodeGdeService paForNodeGdeService;

	private Long idDominio;
	private Long idApplicazione;

	@BeforeEach
	void creaAnagrafiche() {
		DominioEntity dominio = dominioRepository.save(DominioEntity.builder()
				.codDominio(COD_DOMINIO)
				.ragioneSociale("Comune di Test (canale interno)")
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
		dominio.setStazione(stazione);
		dominioRepository.save(dominio);

		// L'utenza va salvata PRIMA dell'applicazione: quest'ultima la referenzia per id
		// (associazione applicativa richiesta dall'issue, bug di revisione corretto
		// 2026-11-04 — vedi anche utenzaNonApplicativaAbilitata, che NON crea questa riga).
		UtenzaEntity utenza = new UtenzaEntity();
		utenza.setPrincipal(PRINCIPAL_UTENZA);
		utenza.setPrincipalOriginale(PRINCIPAL_UTENZA);
		utenza.setAbilitato(true);
		utenza.setAutorizzazioneDominiStar(false);
		utenza.setPassword(passwordEncoder.encode(PASSWORD_UTENZA));
		utenza = utenzaRepository.save(utenza);

		ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-TEST-INTERNO")
				.autoIuv(true)
				.firmaRicevuta("N")
				.trusted(true)
				.idUtenza(utenza.getId())
				.build());
		idApplicazione = applicazione.getId();
	}

	/**
	 * Utenza abilitata, con credenziali valide, ma NON associata a nessuna riga in
	 * {@code applicazioni} — es. un'utenza "operatore" dello stesso schema condiviso {@code
	 * utenze}, pensata per un login umano altrove (es. govpay-console-api), non per questo
	 * contratto. Bug di revisione corretto 2026-11-04: prima arrivava comunque
	 * all'acquisizione RT, bastava essere abilitata.
	 */
	private void utenzaNonApplicativaAbilitata(String principal, String password) {
		UtenzaEntity utenza = new UtenzaEntity();
		utenza.setPrincipal(principal);
		utenza.setPrincipalOriginale(principal);
		utenza.setAbilitato(true);
		utenza.setAutorizzazioneDominiStar(false);
		utenza.setPassword(passwordEncoder.encode(password));
		utenzaRepository.save(utenza);
	}

	@AfterEach
	void pulisci() {
		dominioRepository.deleteAll();
		stazioneRepository.deleteAll();
		intermediarioRepository.deleteAll();
		applicazioneRepository.deleteAll();
		utenzaRepository.deleteAll();
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
		pendenza.setStato(StatoPendenza.NON_ESEGUITO);
		opzione.addPendenza(pendenza);

		VocePendenza voce = new VocePendenza();
		voce.setIdVocePendenza("voce-" + codDocumento);
		voce.setImporto(importo);
		voce.setDescrizione("test");
		voce.setIndice(1);
		voce.setStato(StatoVocePendenza.NON_ESEGUITO);
		voce.setIdTributo(1L);
		voce.setTipoContabilita(TipoContabilita.CAPITOLO);
		voce.setCodiceContabilita("001");
		pendenza.addVocePendenza(voce);

		posizioneDebitoriaService.crea(DatiPosizioneDebitoria.da(posizione));
		return identificativi;
	}

	private ResponseEntity<String> inviaSulCanaleInterno(String body, String username, String password,
			String onBehalfOf) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_XML);
		headers.add("SOAPAction", "paSendRT");
		if (username != null) {
			headers.setBasicAuth(username, password);
		}
		if (onBehalfOf != null) {
			headers.add("GOVPAY-ON-BEHALF-OF", onBehalfOf);
		}
		return restTemplate.postForEntity("http://localhost:" + port + "/internal/PagamentiTelematiciCCPservice", new HttpEntity<>(body, headers),
				String.class);
	}

	/**
	 * Canale Nodo (stesso metodo HEADER di {@link PaForNodeEndpointTest}, il piu' semplice da
	 * simulare via HTTP nel profilo {@code test}) — usato per provare che il percorso interno
	 * rifiuta questo tipo di credenziale (bug di revisione corretto 2026-11-04, finding #2) e
	 * che l'header operatore viene ignorato su questo canale (finding #5).
	 */
	private ResponseEntity<String> inviaSulCanaleNodo(String body, String headerPrincipal, String onBehalfOf) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_XML);
		headers.add("SOAPAction", "paSendRT");
		if (headerPrincipal != null) {
			headers.add(HEADER_PRINCIPAL, headerPrincipal);
		}
		if (onBehalfOf != null) {
			headers.add("GOVPAY-ON-BEHALF-OF", onBehalfOf);
		}
		return restTemplate.postForEntity("http://localhost:" + port + "/ws", new HttpEntity<>(body, headers),
				String.class);
	}

	/** Come {@link #inviaSulCanaleInterno}, ma con credenziali BASIC su un path diverso (Nodo). */
	private ResponseEntity<String> inviaCredenzialiBasicSulCanaleNodo(String body, String username, String password) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_XML);
		headers.add("SOAPAction", "paSendRT");
		headers.setBasicAuth(username, password);
		return restTemplate.postForEntity("http://localhost:" + port + "/ws", new HttpEntity<>(body, headers),
				String.class);
	}

	/** Come {@link #inviaSulCanaleInterno}, ma con l'header HEADER (stile intermediario) sul path interno. */
	private ResponseEntity<String> inviaAutenticazioneIntermediarioSulCanaleInterno(String body,
			String headerPrincipal) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_XML);
		headers.add("SOAPAction", "paSendRT");
		headers.add(HEADER_PRINCIPAL, headerPrincipal);
		return restTemplate.postForEntity("http://localhost:" + port + "/internal/PagamentiTelematiciCCPservice", new HttpEntity<>(body, headers),
				String.class);
	}

	private String envelopeSendRT(String idBrokerPA, String idStation, String receiptId, String fiscalCode,
			String creditorReferenceId, double paymentAmount, int idTransfer, double transferAmount) {
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
				        <officeName>ufficio</officeName>
				        <idPSP>ID-PSP</idPSP>
				        <pspFiscalCode>00000000000</pspFiscalCode>
				        <PSPCompanyName>Psp Test</PSPCompanyName>
				        <idChannel>ID-CH</idChannel>
				        <paymentMethod>creditCard</paymentMethod>
				        <fee>0.00</fee>
				        <paymentDateTime>2026-11-04T10:00:00</paymentDateTime>
				        <transferList>
				          <transfer>
				            <idTransfer>%s</idTransfer>
				            <transferAmount>%s</transferAmount>
				            <fiscalCodePA>%s</fiscalCodePA>
				            <IBAN>IT00000000000000000000000</IBAN>
				          </transfer>
				        </transferList>
				      </receipt>
				    </pafn:paSendRTReq>
				  </soapenv:Body>
				</soapenv:Envelope>
				""".formatted(fiscalCode, idBrokerPA, idStation, receiptId, fiscalCode, creditorReferenceId,
				paymentAmount, idTransfer, transferAmount, fiscalCode);
	}

	/**
	 * Finding #4 di revisione (2026-11-05): il WSDL e' il contratto pubblico pagoPA, deve
	 * essere raggiungibile senza credenziali con la convenzione {@code ?wsdl} sulla stessa URL
	 * del servizio — su ENTRAMBI i canali (qui l'interno; {@link
	 * PaForNodeEndpointTest#wsdlEspostoSullaPathAttesa} copre la vecchia convenzione a nome
	 * del bean sul Nodo).
	 *
	 * <p>Finding #3 del giro successivo di revisione (2026-11-05): verifica anche il {@code
	 * soap:address} — deve indicare il canale da cui e' stato scaricato, non sempre {@code
	 * /ws} (il file statico lo ha fisso, {@code PaForNodeWsdlDefinitionHandlerAdapter} lo
	 * corregge solo per il canale interno).
	 */
	@Test
	void wsdlPubblicoDisponibileSenzaCredenzialiSulCanaleInternoConLaConvenzioneWsdl() {
		ResponseEntity<String> risposta = restTemplate.getForEntity(
				"http://localhost:" + port + "/internal/PagamentiTelematiciCCPservice?wsdl", String.class);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("definitions", "paForNode");
		assertThat(risposta.getBody()).contains(
				"soap:address location=\"http://localhost:" + port + "/internal/PagamentiTelematiciCCPservice\"");
	}

	@Test
	void wsdlPubblicoDisponibileSenzaCredenzialiSulCanaleNodoConLaConvenzioneWsdl() {
		ResponseEntity<String> risposta = restTemplate.getForEntity("http://localhost:" + port + "/ws?wsdl",
				String.class);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("definitions", "paForNode");
		assertThat(risposta.getBody()).contains("soap:address location=\"http://localhost:" + port + "/ws\"");
	}

	/** La pubblicazione pubblica del WSDL (GET) non deve aprire anche le POST sullo stesso path. */
	@Test
	void postSulCanaleInternoRestaAutenticataNonostanteIlWsdlPubblico() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-12", 16.00);

		assertThatThrownBy(() -> inviaSulCanaleInterno(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				null, null, null)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
	}

	/**
	 * Bug di revisione corretto 2026-11-05 (finding #1): {@code isPathCanaleInterno}
	 * confrontava {@code getRequestURI()} con un prefisso — aggirabile con una richiesta
	 * come questa, che il mapping a wildcard del canale Nodo ({@code /ws/*}) accetta
	 * comunque. Deve restare classificata come canale NODO (il servlet path effettivo e'
	 * {@code /ws}, non il path interno): credenziali Basic qui devono essere rifiutate per
	 * mismatch, non accettate come se fosse il canale interno.
	 */
	@Test
	void unPathCheContieneIlPrefissoInternoSulWildcardDelNodoRestaCanaleNodo() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-13", 16.00);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_XML);
		headers.add("SOAPAction", "paSendRT");
		headers.setBasicAuth(PRINCIPAL_UTENZA, PASSWORD_UTENZA);

		ResponseEntity<String> risposta = restTemplate.postForEntity(
				"http://localhost:" + port + "/ws/internal/qualcosa",
				new HttpEntity<>(envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(),
						COD_DOMINIO, identificativi.iuv(), 16.00, 1, 16.00), headers),
				String.class);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_SYSTEM_ERROR</faultCode>",
				"canale e metodo di autenticazione non corrispondono");
	}

	/**
	 * Bug di revisione corretto 2026-11-05 (finding #2): solo {@code paSendRT}/{@code
	 * paSendRTV2} sono previste sul canale interno — le altre operazioni devono essere
	 * rifiutate su questo path anche con un'autenticazione da intermediario perfettamente
	 * valida (altrimenti un intermediario autenticato potrebbe eseguire {@code paGetPayment}
	 * sul canale interno, bypassando la restrizione che l'issue prevede solo per l'invio
	 * delle ricevute).
	 */
	@Test
	void paGetPaymentSulCanaleInternoVieneRifiutatoAncheConAutenticazioneDaIntermediario() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-14", 16.00);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_XML);
		headers.add("SOAPAction", "paGetPayment");
		headers.add(HEADER_PRINCIPAL, PRINCIPAL_INTERMEDIARIO);

		ResponseEntity<String> risposta = restTemplate.postForEntity(
				"http://localhost:" + port + "/internal/PagamentiTelematiciCCPservice",
				new HttpEntity<>(envelopeGetPayment(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, COD_DOMINIO,
						identificativi.numeroAvviso()), headers),
				String.class);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_SYSTEM_ERROR</faultCode>",
				"non disponibile sul canale interno");
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

	/**
	 * Bug di revisione corretto 2026-11-05 (ulteriore giro, finding #1): {@code
	 * verificaCanaleNodo} rifiutava solo in base al path, non al metodo di autenticazione —
	 * un'utenza applicativa il cui principal coincide (qui deliberatamente, per costruire il
	 * caso) con quello di un intermediario registrato avrebbe superato anche il successivo
	 * confronto principal/intermediario, eseguendo {@code paGetPayment} sul canale Nodo con
	 * credenziali pensate per quello interno. Qui si usa VOLUTAMENTE lo stesso valore di
	 * {@code PRINCIPAL_INTERMEDIARIO} come principal dell'utenza, per provare che il
	 * rifiuto avviene PRIMA di quel confronto (sul solo metodo di autenticazione), non grazie
	 * a una differenza di principal che nasconderebbe il problema.
	 */
	@Test
	void paGetPaymentSulCanaleNodoVieneRifiutatoSeAutenticatoConUtenzaApplicativaAncheConPrincipalCoincidente() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-15", 16.00);
		String passwordCoincidente = "password-coincidente";
		UtenzaEntity utenzaConPrincipalCoincidente = new UtenzaEntity();
		utenzaConPrincipalCoincidente.setPrincipal(PRINCIPAL_INTERMEDIARIO);
		utenzaConPrincipalCoincidente.setPrincipalOriginale(PRINCIPAL_INTERMEDIARIO);
		utenzaConPrincipalCoincidente.setAbilitato(true);
		utenzaConPrincipalCoincidente.setAutorizzazioneDominiStar(false);
		utenzaConPrincipalCoincidente.setPassword(passwordEncoder.encode(passwordCoincidente));
		utenzaRepository.save(utenzaConPrincipalCoincidente);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_XML);
		headers.add("SOAPAction", "paGetPayment");
		headers.setBasicAuth(PRINCIPAL_INTERMEDIARIO, passwordCoincidente);

		ResponseEntity<String> risposta = restTemplate.postForEntity("http://localhost:" + port + "/ws",
				new HttpEntity<>(envelopeGetPayment(COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, COD_DOMINIO,
						identificativi.numeroAvviso()), headers),
				String.class);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_SYSTEM_ERROR</faultCode>",
				"metodo di autenticazione non ammesso");
	}

	/**
	 * Prova sia che l'autenticazione BASIC funziona sullo stesso servlet del Nodo, sia che il
	 * confronto principal/intermediario e' bypassato: {@code PRINCIPAL_INTERMEDIARIO} (quello
	 * registrato sull'intermediario dichiarato in {@code idBrokerPA}) non e' mai presentato
	 * come credenziale — solo {@code PRINCIPAL_UTENZA} lo e', e non corrisponderebbe comunque
	 * all'intermediario se il confronto venisse eseguito.
	 */
	@Test
	void paSendRTSulCanaleInternoFunzionaConUtenzaApplicativaEBypassaIlConfrontoPrincipalIntermediario() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-1", 16.00);

		ResponseEntity<String> risposta = inviaSulCanaleInterno(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				PRINCIPAL_UTENZA, PASSWORD_UTENZA, null);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>");
	}

	@Test
	void paSendRTSulCanaleInternoRifiutaSenzaCredenziali() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-2", 16.00);

		assertThatThrownBy(() -> inviaSulCanaleInterno(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				null, null, null)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
	}

	@Test
	void paSendRTSulCanaleInternoRifiutaCredenzialiErrate() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-3", 16.00);

		assertThatThrownBy(() -> inviaSulCanaleInterno(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				PRINCIPAL_UTENZA, "password-sbagliata", null))
				.isInstanceOf(HttpClientErrorException.Unauthorized.class);
	}

	/**
	 * L'header e' informativo, letto comunque dal filtro GDE indipendentemente dal percorso —
	 * qui si verifica solo che, quando presente sul canale interno (il suo caso d'uso reale),
	 * arrivi davvero nell'evento come operatore, distinto dal principal tecnico.
	 */
	@Test
	void operatoreGovpayOnBehalfOfVieneRegistratoNellEventoGdeDistintoDalPrincipalTecnico()
			throws InterruptedException {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-4", 16.00);

		ResponseEntity<String> risposta = inviaSulCanaleInterno(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				PRINCIPAL_UTENZA, PASSWORD_UTENZA, "operatore-umano-test");

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);

		GdeEventInfo evento = attendiEventoGde(e -> "paSendRT".equals(e.getTipoEvento()));
		assertThat(evento.getPrincipal()).isEqualTo(PRINCIPAL_UTENZA);
		assertThat(evento.getUtente()).isEqualTo("operatore-umano-test");
	}

	@Test
	void assenzaDellHeaderOnBehalfOfNonPopolaLutenteNellEventoGde() throws InterruptedException {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-5", 16.00);

		ResponseEntity<String> risposta = inviaSulCanaleInterno(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				PRINCIPAL_UTENZA, PASSWORD_UTENZA, null);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);

		GdeEventInfo evento = attendiEventoGde(e -> "paSendRT".equals(e.getTipoEvento()));
		assertThat(evento.getUtente()).isNull();
	}

	private String envelopeSendRTV2(String idBrokerPA, String idStation, String receiptId, String fiscalCode,
			String creditorReferenceId, double paymentAmount, int idTransfer, double transferAmount) {
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
				""".formatted(fiscalCode, idBrokerPA, idStation, receiptId, fiscalCode, creditorReferenceId,
				paymentAmount, idTransfer, transferAmount, fiscalCode);
	}

	/**
	 * Bug di revisione corretto 2026-11-04 (finding #1): un'utenza abilitata ma NON associata
	 * a nessuna applicazione non deve arrivare all'acquisizione RT — solo esistenza+
	 * abilitazione (verificate dalla catena di sicurezza all'autenticazione) non bastano.
	 */
	@Test
	void paSendRTSulCanaleInternoRifiutaUtenzaAbilitataMaNonApplicativa() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-6", 16.00);
		utenzaNonApplicativaAbilitata("operatore-non-applicativo", "password-operatore");

		ResponseEntity<String> risposta = inviaSulCanaleInterno(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				"operatore-non-applicativo", "password-operatore", null);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_SYSTEM_ERROR</faultCode>",
				"non associata a un'applicazione");
	}

	/**
	 * Bug di revisione corretto 2026-11-04 (finding #2): una credenziale Basic valida non deve
	 * autenticare una richiesta sul path Nodo — solo il tipo di credenziale non basta, serve
	 * che corrisponda anche al path su cui e' arrivata.
	 */
	@Test
	void paSendRTSulCanaleNodoRifiutaCredenzialiBasicDiUnUtenzaApplicativa() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-7", 16.00);

		ResponseEntity<String> risposta = inviaCredenzialiBasicSulCanaleNodo(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				PRINCIPAL_UTENZA, PASSWORD_UTENZA);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_SYSTEM_ERROR</faultCode>",
				"canale e metodo di autenticazione non corrispondono");
	}

	/**
	 * Come {@link #paSendRTSulCanaleNodoRifiutaCredenzialiBasicDiUnUtenzaApplicativa}, il
	 * verso opposto: autenticazione da intermediario (HEADER, stand-in del certificato
	 * client) sul path interno deve essere rifiutata, non trattata come canale Nodo per il
	 * solo fatto di non essere Basic.
	 */
	@Test
	void paSendRTSulCanaleInternoRifiutaAutenticazioneDaIntermediario() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-8", 16.00);

		ResponseEntity<String> risposta = inviaAutenticazioneIntermediarioSulCanaleInterno(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				PRINCIPAL_INTERMEDIARIO);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_SYSTEM_ERROR</faultCode>",
				"canale e metodo di autenticazione non corrispondono");
	}

	/**
	 * Bug di revisione corretto 2026-11-04 (finding #5): l'header e' interpretato SOLO sul
	 * canale interno — su quello Nodo, anche se presente, non deve finire nell'evento GDE
	 * (altrimenti una chiamata Nodo potrebbe attribuirsi un operatore arbitrario).
	 */
	@Test
	void operatoreGovpayOnBehalfOfIgnoratoSulCanaleNodo() throws InterruptedException {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-9", 16.00);

		ResponseEntity<String> risposta = inviaSulCanaleNodo(
				envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				PRINCIPAL_INTERMEDIARIO, "operatore-arbitrario");

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>");

		GdeEventInfo evento = attendiEventoGde(e -> "paSendRT".equals(e.getTipoEvento()));
		assertThat(evento.getUtente()).isNull();
	}

	/** Copertura richiesta in revisione: il canale interno funziona anche per paSendRTV2. */
	@Test
	void paSendRTV2SulCanaleInternoFunzionaConUtenzaApplicativa() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-10", 16.00);

		ResponseEntity<String> risposta = inviaSulCanaleInterno(
				envelopeSendRTV2(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-v2-" + identificativi.iuv(), COD_DOMINIO,
						identificativi.iuv(), 16.00, 1, 16.00),
				PRINCIPAL_UTENZA, PASSWORD_UTENZA, null);

		assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(risposta.getBody()).contains("<outcome>OK</outcome>");
	}

	/**
	 * Copertura richiesta in revisione: un secondo invio della STESSA ricevuta sul canale
	 * interno deve essere segnalato come duplicato, stesso comportamento del canale Nodo
	 * (issue #6) — l'autorizzazione del canale non deve interferire con questo controllo.
	 */
	@Test
	void secondoInvioDellaStessaRicevutaSulCanaleInternoVieneSegnalatoComeDuplicato() {
		IdentificativiPagamento identificativi = creaPendenza("doc-interno-11", 16.00);
		String richiesta = envelopeSendRT(COD_INTERMEDIARIO, COD_STAZIONE, "receipt-" + identificativi.iuv(),
				COD_DOMINIO, identificativi.iuv(), 16.00, 1, 16.00);

		ResponseEntity<String> prima = inviaSulCanaleInterno(richiesta, PRINCIPAL_UTENZA, PASSWORD_UTENZA, null);
		ResponseEntity<String> seconda = inviaSulCanaleInterno(richiesta, PRINCIPAL_UTENZA, PASSWORD_UTENZA, null);

		assertThat(prima.getBody()).contains("<outcome>OK</outcome>");
		assertThat(seconda.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(seconda.getBody()).contains("<outcome>KO</outcome>", "<faultCode>PAA_RECEIPT_DUPLICATA</faultCode>");
	}

	/** Stesso pattern di {@link PaForNodeEndpointTest#attendiEventoGde}, vedi il suo Javadoc. */
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
}
