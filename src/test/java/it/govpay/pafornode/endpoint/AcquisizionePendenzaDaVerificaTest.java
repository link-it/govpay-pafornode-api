package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.common.repository.TipoVersamentoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.common.utils.IuvUtils;
import it.govpay.ec.client.beans.NuovaPendenza;
import it.govpay.ec.client.beans.NuovaVocePendenza;
import it.govpay.ec.client.beans.PendenzaVerificata;
import it.govpay.ec.client.beans.Soggetto;
import it.govpay.ec.client.beans.StatoPendenzaVerificata;
import it.govpay.ec.client.beans.TipoSoggetto;
import it.govpay.pafornode.gde.PaForNodeGdeService;
import it.govpay.pafornode.verifica.VerificaClient;

/**
 * Unita' operativa delle pendenze acquisite dal gestionale tramite "Verifica", sulle tre
 * operazioni che la interrogano: richiesta SOAP reale, contesto Spring completo e solo il
 * client del gestionale simulato.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AcquisizionePendenzaDaVerificaTest {

	private static final String COD_DOMINIO = "12345678901";
	private static final String COD_INTERMEDIARIO = "BROKER-TEST";
	private static final String COD_STAZIONE = "STAZIONE-TEST";
	private static final String PRINCIPAL_INTERMEDIARIO = "CN=broker-test.example.it";
	private static final String CONNETTORE_VERIFICA = "CONNETTORE-VERIFICA";
	private static final String IBAN = "IT60X0542811101000000123456";

	/** Tabelle su cui non deve restare nulla quando l'acquisizione fallisce. */
	private static final List<String> TABELLE_ACQUISIZIONE = List.of("documenti", "debitori", "pd_debitori",
			"opzioni_pagamento", "versamenti", "singoli_versamenti", "rpt", "notifiche");

	enum Operazione {
		VERIFY("paVerifyPaymentNotice", "paVerifyPaymentNoticeReq"),
		GET_PAYMENT("paGetPayment", "paGetPaymentReq"),
		GET_PAYMENT_V2("paGetPaymentV2", "paGetPaymentV2Request");

		private final String soapAction;
		private final String richiesta;

		Operazione(String soapAction, String richiesta) {
			this.soapAction = soapAction;
			this.richiesta = richiesta;
		}
	}

	/** Configurazioni dell'anagrafica per cui la creazione deve fallire. */
	enum UnitaOperativaNonUtilizzabile {
		EC_ASSENTE(null, null),
		EC_DISABILITATA(null, "EC"),
		ESPLICITA_ASSENTE("UO1", null),
		ESPLICITA_DISABILITATA("UO1", "UO1");

		/** Codice indicato dal gestionale. */
		private final String indicata;
		/** Codice censito come disabilitato. */
		private final String disabilitata;

		UnitaOperativaNonUtilizzabile(String indicata, String disabilitata) {
			this.indicata = indicata;
			this.disabilitata = disabilitata;
		}
	}

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
	private UnitaOperativaRepository unitaOperativaRepository;
	@Autowired
	private TipoVersamentoRepository tipoVersamentoRepository;
	@Autowired
	private TipoVersamentoDominioRepository tipoVersamentoDominioRepository;
	@Autowired
	private IbanAccreditoRepository ibanAccreditoRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private VerificaClient verificaClient;
	@MockitoBean
	private PaForNodeGdeService paForNodeGdeService;

	private DominioEntity dominio;
	private String numeroAvviso;

	@BeforeEach
	void creaAnagrafiche() {
		dominio = dominioRepository.save(DominioEntity.builder()
				.codDominio(COD_DOMINIO)
				.ragioneSociale("Comune di Test")
				.abilitato(true)
				.intermediato(false)
				.scaricaFr(false)
				.auxDigit(1)
				.build());
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
		dominio = dominioRepository.save(dominio);

		jdbcTemplate.update("INSERT INTO utenze (id, principal, principal_originale, autorizzazione_domini_star) "
				+ "VALUES (1, 'app-test', 'app-test', true)");
		applicazioneRepository.save(ApplicazioneEntity.builder()
				.codApplicazione("APP-TEST")
				.autoIuv(true)
				.firmaRicevuta("N")
				.trusted(true)
				.idUtenza(1L)
				.regExp(".*")
				.codConnettoreIntegrazione(CONNETTORE_VERIFICA)
				.build());

		TipoVersamentoEntity tipoVersamento = new TipoVersamentoEntity();
		tipoVersamento.setCodTipoVersamento("TARI");
		tipoVersamento.setDescrizione("Tassa rifiuti");
		tipoVersamento.setAbilitato(true);
		tipoVersamento.setPagaTerzi(false);
		tipoVersamento.setBoAbilitato(false);
		tipoVersamento.setPagAbilitato(false);
		tipoVersamento.setAvvMailPromAvvAbilitato(false);
		tipoVersamento.setAvvMailPromRicAbilitato(false);
		tipoVersamento.setAvvMailPromScadAbilitato(false);
		tipoVersamento.setAvvAppIoPromAvvAbilitato(false);
		tipoVersamento.setAvvAppIoPromRicAbilitato(false);
		tipoVersamento.setAvvAppIoPromScadAbilitato(false);
		tipoVersamento = tipoVersamentoRepository.save(tipoVersamento);
		TipoVersamentoDominioEntity tipoVersamentoDominio = new TipoVersamentoDominioEntity();
		tipoVersamentoDominio.setTipoVersamento(tipoVersamento);
		tipoVersamentoDominio.setDominio(dominio);
		tipoVersamentoDominioRepository.save(tipoVersamentoDominio);

		IbanAccreditoEntity iban = new IbanAccreditoEntity();
		iban.setCodIban(IBAN);
		iban.setPostale(false);
		iban.setAbilitato(true);
		iban.setDominio(dominio);
		ibanAccreditoRepository.save(iban);

		numeroAvviso = IuvUtils.genera(1, "", null, null, System.nanoTime() % 1_000_000_000_000_00L)
				.numeroAvviso();
	}

	@AfterEach
	void pulisci() {
		jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
		jdbcTemplate.queryForList("SELECT table_name FROM information_schema.tables "
				+ "WHERE UPPER(table_schema) = 'PUBLIC' AND table_type = 'BASE TABLE'", String.class)
				.forEach(tabella -> jdbcTemplate.execute("TRUNCATE TABLE \"" + tabella + "\""));
		jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
	}

	@ParameterizedTest
	@EnumSource(Operazione.class)
	void senzaUnitaOperativaSalvaLaUnitaOperativaEcSenzaEsporlaNellaRisposta(Operazione operazione) {
		Long idEc = salvaUnitaOperativa("EC", true);
		rispostaDelGestionale(null, 10, null);

		String risposta = invia(operazione);

		assertThat(risposta).contains("<outcome>OK</outcome>").doesNotContain("officeName");
		assertThat(unitaOperativaDellePosizioni()).containsExactly(idEc);
	}

	static Stream<Arguments> unitaOperativeEsplicite() {
		// stato della UO EC: assente (null) o disabilitata
		return Arrays.stream(Operazione.values())
				.flatMap(operazione -> Stream.of(arguments(operazione, null), arguments(operazione, false)));
	}

	@ParameterizedTest
	@MethodSource("unitaOperativeEsplicite")
	void unitaOperativaEsplicitaValidaAccettataAncheSenzaUnaEcUtilizzabile(Operazione operazione, Boolean statoEc) {
		if (statoEc != null) {
			salvaUnitaOperativa("EC", statoEc);
		}
		Long idUo = salvaUnitaOperativa("UO1", true);
		rispostaDelGestionale("UO1", 10, null);

		String risposta = invia(operazione);

		assertThat(risposta).contains("<outcome>OK</outcome>").contains("<officeName>Ufficio UO1</officeName>");
		assertThat(unitaOperativaDellePosizioni()).containsExactly(idUo);
	}

	static Stream<Arguments> creazioniRifiutate() {
		return Arrays.stream(Operazione.values()).flatMap(operazione -> Arrays
				.stream(UnitaOperativaNonUtilizzabile.values()).map(caso -> arguments(operazione, caso)));
	}

	@ParameterizedTest
	@MethodSource("creazioniRifiutate")
	void unitaOperativaNonUtilizzabileRispondeConSystemErrorSenzaPersistereNulla(Operazione operazione,
			UnitaOperativaNonUtilizzabile caso) {
		if (caso.indicata != null) {
			salvaUnitaOperativa("EC", true);
		}
		if (caso.disabilitata != null) {
			salvaUnitaOperativa(caso.disabilitata, false);
		}
		rispostaDelGestionale(caso.indicata, 10, null);

		String risposta = invia(operazione);

		String codiceAtteso = caso.indicata != null ? caso.indicata : "EC";
		assertThat(risposta).contains("<outcome>KO</outcome>").contains("<faultCode>PAA_SYSTEM_ERROR</faultCode>")
				.contains("[" + codiceAtteso + "]");
		for (String tabella : TABELLE_ACQUISIZIONE) {
			assertThat(conta(tabella)).as(tabella).isZero();
		}
	}

	@ParameterizedTest
	@EnumSource(Operazione.class)
	void riacquisizioneSenzaUnitaOperativaConservaQuellaDellaPosizione(Operazione operazione) {
		Long idUo = salvaUnitaOperativa("UO1", true);
		// Prima acquisizione con validita' gia' decorsa: alla richiesta successiva la pendenza si riacquisisce.
		rispostaDelGestionale("UO1", 10, LocalDate.now().minusDays(2));
		assertThat(invia(operazione)).contains("<outcome>OK</outcome>");
		// Nessuna UO EC e UO della posizione disabilitata: qualunque controllo di creazione fallirebbe.
		UnitaOperativaEntity unitaOperativa = unitaOperativaRepository.findById(idUo).orElseThrow();
		unitaOperativa.setAbilitato(false);
		unitaOperativaRepository.save(unitaOperativa);
		rispostaDelGestionale(null, 20, LocalDate.now().plusDays(10));

		String risposta = invia(operazione);

		assertThat(risposta).contains("<outcome>OK</outcome>");
		verify(verificaClient, times(2)).getAvviso(any(), any(), any());
		assertThat(jdbcTemplate.queryForObject("SELECT importo_totale FROM versamenti", Double.class))
				.isEqualTo(20.0);
		assertThat(unitaOperativaDellePosizioni()).containsExactly(idUo);
	}

	private Long salvaUnitaOperativa(String codUo, boolean abilitata) {
		UnitaOperativaEntity unitaOperativa = new UnitaOperativaEntity();
		unitaOperativa.setCodUo(codUo);
		unitaOperativa.setUoDenominazione("Ufficio " + codUo);
		unitaOperativa.setAbilitato(abilitata);
		unitaOperativa.setDominio(dominio);
		return unitaOperativaRepository.save(unitaOperativa).getId();
	}

	private void rispostaDelGestionale(String idUnitaOperativa, int importo, LocalDate dataValidita) {
		Soggetto soggetto = new Soggetto();
		soggetto.setTipo(TipoSoggetto.F);
		soggetto.setIdentificativo("RSSMRA80A01H501U");
		soggetto.setAnagrafica("Mario Rossi");

		NuovaVocePendenza voce = new NuovaVocePendenza();
		voce.setIdVocePendenza("voce-1");
		voce.setImporto(BigDecimal.valueOf(importo));
		voce.setDescrizione("voce acquisita");
		voce.setIbanAccredito(IBAN);
		voce.setCodiceTassonomicoPagoPA("9/3321");

		NuovaPendenza pendenza = new NuovaPendenza();
		pendenza.setIdPendenza("pend-verifica");
		pendenza.setIdTipoPendenza("TARI");
		pendenza.setIdDominio(COD_DOMINIO);
		pendenza.setIdUnitaOperativa(idUnitaOperativa);
		pendenza.setNumeroAvviso(numeroAvviso);
		pendenza.setCausale("causale acquisita");
		pendenza.setSoggettoPagatore(soggetto);
		pendenza.setImporto(BigDecimal.valueOf(importo));
		pendenza.setDataValidita(dataValidita);
		pendenza.setVoci(List.of(voce));

		PendenzaVerificata risposta = new PendenzaVerificata();
		risposta.setStato(StatoPendenzaVerificata.NON_ESEGUITA);
		risposta.setPendenza(pendenza);
		when(verificaClient.getAvviso(CONNETTORE_VERIFICA, COD_DOMINIO, numeroAvviso)).thenReturn(risposta);
	}

	private String invia(Operazione operazione) {
		String body = """
				<?xml version="1.0" encoding="UTF-8"?>
				<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
				                  xmlns:pafn="http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd">
				  <soapenv:Body>
				    <pafn:%1$s>
				      <idPA>%2$s</idPA>
				      <idBrokerPA>%3$s</idBrokerPA>
				      <idStation>%4$s</idStation>
				      <qrCode>
				        <fiscalCode>%2$s</fiscalCode>
				        <noticeNumber>%5$s</noticeNumber>
				      </qrCode>
				    </pafn:%1$s>
				  </soapenv:Body>
				</soapenv:Envelope>
				""".formatted(operazione.richiesta, COD_DOMINIO, COD_INTERMEDIARIO, COD_STAZIONE, numeroAvviso);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_XML);
		headers.add("SOAPAction", operazione.soapAction);
		headers.add("X-Pre-Auth-User", PRINCIPAL_INTERMEDIARIO);
		return restTemplate.postForEntity("http://localhost:" + port + "/ws", new HttpEntity<>(body, headers),
				String.class).getBody();
	}

	private List<Long> unitaOperativaDellePosizioni() {
		return jdbcTemplate.queryForList("SELECT id_unita_operativa FROM documenti", Long.class);
	}

	private long conta(String tabella) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tabella, Long.class);
	}
}
