package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceipt;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtSubject;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Request;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.rpt.RptAttivazioneService;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pafornode.ricevuta.AcquisizioneRicevutaService;
import it.govpay.pafornode.ricevuta.DatiRicevutaPagamento;

/**
 * Unit test diretto su {@link PaSendRTService} (nessun contesto Spring, nessuna chiamata
 * HTTP) — stesso principio di {@code PaForNodeEndpointAutorizzazioneTest}: isola i rifiuti
 * precoci (autorizzazione, idempotenza, RPT sconosciuta) senza dover passare per HTTP o
 * costruire RPT/ricevute realistiche complete. Il percorso a lieto fine (RPT trovata,
 * ricevuta valida, acquisizione riuscita) e' coperto dal test di integrazione SOAP in
 * {@code PaForNodeEndpointTest}.
 */
class PaSendRTServiceTest {

	private static final String ID_BROKER_PA = "BROKER-TEST";
	private static final String PRINCIPAL_REGISTRATO = "CN=broker-test.example.it";
	private static final String COD_DOMINIO = "12345678901";
	private static final String IUV = "100000000000000001";

	private final IntermediarioRepository intermediarioRepository = mock(IntermediarioRepository.class);
	private final StazioneRepository stazioneRepository = mock(StazioneRepository.class);
	private final DominioRepository dominioRepository = mock(DominioRepository.class);
	private final PendenzaRepository pendenzaRepository = mock(PendenzaRepository.class);
	private final RptRepository rptRepository = mock(RptRepository.class);
	private final RptAttivazioneService rptAttivazioneService = mock(RptAttivazioneService.class);
	private final AcquisizioneRicevutaService acquisizioneRicevutaService = mock(AcquisizioneRicevutaService.class);

	private final PaSendRTService service = new PaSendRTService(intermediarioRepository, stazioneRepository,
			dominioRepository, pendenzaRepository, rptRepository, rptAttivazioneService,
			acquisizioneRicevutaService, Clock.systemDefaultZone());

	@AfterEach
	void pulisciContestoSicurezza() {
		SecurityContextHolder.clearContext();
	}

	private void autenticaCome(String principal) {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null));
	}

	private PaSendRTReq richiesta(String receiptId) {
		CtSubject debtor = new CtSubject();
		CtReceipt receipt = new CtReceipt();
		receipt.setReceiptId(receiptId);
		receipt.setNoticeNumber("300000000000000001");
		receipt.setFiscalCode(COD_DOMINIO);
		receipt.setOutcome(StOutcome.OK);
		receipt.setCreditorReferenceId(IUV);
		receipt.setPaymentAmount(java.math.BigDecimal.TEN);
		receipt.setDescription("test");
		receipt.setCompanyName("Comune di Test");
		receipt.setDebtor(debtor);
		receipt.setIdPSP("PSP1");
		receipt.setPSPCompanyName("Psp Uno");
		receipt.setIdChannel("CH1");
		receipt.setChannelDescription("canale");

		PaSendRTReq request = new PaSendRTReq();
		request.setIdPA(COD_DOMINIO);
		request.setIdBrokerPA(ID_BROKER_PA);
		request.setIdStation("STAZIONE-TEST");
		request.setReceipt(receipt);
		return request;
	}

	@Test
	void rifiutaSeIntermediarioSconosciuto() {
		when(intermediarioRepository.findByCodIntermediario(ID_BROKER_PA)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.eseguiSendRT(richiesta("receipt-1")))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_ID_INTERMEDIARIO_ERRATO);
	}

	@Test
	void rifiutaSeStazioneSconosciuta() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario(ID_BROKER_PA)
				.principal(PRINCIPAL_REGISTRATO).abilitato(true).build();
		when(intermediarioRepository.findByCodIntermediario(ID_BROKER_PA)).thenReturn(Optional.of(intermediario));
		when(stazioneRepository.findByIntermediarioCodIntermediarioAndCodStazione(any(), any()))
				.thenReturn(Optional.empty());
		autenticaCome(PRINCIPAL_REGISTRATO);

		assertThatThrownBy(() -> service.eseguiSendRT(richiesta("receipt-1")))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_STAZIONE_INT_ERRATA);
	}

	@Test
	void rifiutaSePendenzaSconosciuta() {
		mockAutorizzazioneOk();
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.eseguiSendRT(richiesta("receipt-1")))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_RPT_SCONOSCIUTA);
	}

	@Test
	void rifiutaConReceiptDuplicataSeGiaAcquisita() {
		mockAutorizzazioneOk();
		Pendenza pendenza = new Pendenza();
		pendenza.setId(1L);
		pendenza.setIuv(IUV);
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenza));
		when(pendenzaRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(pendenza));
		when(rptRepository.findByCodDominioAndIuvAndIur(COD_DOMINIO, IUV, "receipt-1"))
				.thenReturn(Optional.of(new Rpt()));

		assertThatThrownBy(() -> service.eseguiSendRT(richiesta("receipt-1")))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_RECEIPT_DUPLICATA);
	}

	/**
	 * A differenza di {@link #ricostruisceStandinSeNonEsisteNessunaRptDelModelloPsp}: qui
	 * esiste GIA' una RPT del modello {@code ATTIVATO_PRESSO_PSP} per questa pendenza (solo
	 * in un altro stato) — lo standin non si applica (vedi Javadoc di classe di
	 * {@link PaSendRTService}), resta {@code PAA_RPT_SCONOSCIUTA}.
	 */
	@Test
	void rifiutaConRptSconosciutaSeEsisteGiaUnaRptDelModelloPspInAltroStato() {
		mockAutorizzazioneOk();
		Pendenza pendenza = new Pendenza();
		pendenza.setId(1L);
		pendenza.setIuv(IUV);
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenza));
		when(pendenzaRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(pendenza));
		when(rptRepository.findByCodDominioAndIuvAndIur(any(), any(), any())).thenReturn(Optional.empty());
		when(rptRepository.findByIdVersamentoAndStatoAndModelloPagamento(1L, "RPT_ACCETTATA_NODO",
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(Optional.empty());
		when(rptRepository.existsByIdVersamentoAndModelloPagamento(1L,
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(true);

		assertThatThrownBy(() -> service.eseguiSendRT(richiesta("receipt-1")))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_RPT_SCONOSCIUTA);
	}

	/**
	 * Come {@link #ricostruisceStandinSeEsisteSoloUnaRptDiUnAltroModello}, ma con la RPT di
	 * un altro modello effettivamente presente anche nello stato {@code RPT_ACCETTATA_NODO}
	 * (non solo in "un altro stato" generico): la ricerca della RPT in attesa deve restare
	 * filtrata per modello anche lei, altrimenti selezionerebbe questa riga invece di cadere
	 * sullo standin — bug di revisione distinto da quello di
	 * {@link #ricostruisceStandinSeEsisteSoloUnaRptDiUnAltroModello} (quella copriva solo il
	 * controllo "esiste nessuna RPT", non la ricerca della RPT in attesa).
	 */
	@Test
	void ricostruisceStandinSeUnaRptDiAltroModelloEAccettataDalNodo() {
		mockAutorizzazioneOk();
		Pendenza pendenza = new Pendenza();
		pendenza.setId(1L);
		pendenza.setIuv(IUV);
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenza));
		when(pendenzaRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(pendenza));
		when(rptRepository.findByCodDominioAndIuvAndIur(any(), any(), any())).thenReturn(Optional.empty());
		// La riga di altro modello ESISTE in RPT_ACCETTATA_NODO, ma la query e' filtrata
		// per modello ATTIVATO_PRESSO_PSP: deve restituire vuoto comunque.
		when(rptRepository.findByIdVersamentoAndStatoAndModelloPagamento(1L, "RPT_ACCETTATA_NODO",
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(Optional.empty());
		when(rptRepository.existsByIdVersamentoAndModelloPagamento(1L,
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(false);
		Rpt rptStandin = new Rpt();
		when(rptAttivazioneService.ricostruisci(eq(pendenza), any(), eq(RptAttivazioneService.VERSIONE_SANP_240),
				eq("receipt-1"), any())).thenReturn(rptStandin);

		service.eseguiSendRT(richiesta("receipt-1"));

		verify(rptAttivazioneService, never()).deserializzaDati(any());
		verify(acquisizioneRicevutaService).acquisisci(eq(pendenza), eq(rptStandin), any(DatiRicevutaPagamento.class));
	}

	/**
	 * Una RPT esiste per questa pendenza, ma di un ALTRO modello di pagamento (non
	 * {@code ATTIVATO_PRESSO_PSP}) — non blocca lo standin, stesso filtro per modello del
	 * legacy ({@code rptBD.getRpt(..., ModelloPagamento.ATTIVATO_PRESSO_PSP, null)}).
	 */
	@Test
	void ricostruisceStandinSeEsisteSoloUnaRptDiUnAltroModello() {
		mockAutorizzazioneOk();
		Pendenza pendenza = new Pendenza();
		pendenza.setId(1L);
		pendenza.setIuv(IUV);
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenza));
		when(pendenzaRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(pendenza));
		when(rptRepository.findByCodDominioAndIuvAndIur(any(), any(), any())).thenReturn(Optional.empty());
		when(rptRepository.findByIdVersamentoAndStatoAndModelloPagamento(1L, "RPT_ACCETTATA_NODO",
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(Optional.empty());
		when(rptRepository.existsByIdVersamentoAndModelloPagamento(1L,
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(false);
		Rpt rptStandin = new Rpt();
		when(rptAttivazioneService.ricostruisci(eq(pendenza), any(), eq(RptAttivazioneService.VERSIONE_SANP_240),
				eq("receipt-1"), any())).thenReturn(rptStandin);

		service.eseguiSendRT(richiesta("receipt-1"));

		verify(acquisizioneRicevutaService).acquisisci(eq(pendenza), eq(rptStandin), any(DatiRicevutaPagamento.class));
	}

	/**
	 * Nessuna RPT del modello {@code ATTIVATO_PRESSO_PSP} per questa pendenza: il pagamento
	 * e' stato attivato direttamente al PSP, senza passare da {@code paGetPayment} —
	 * ricostruzione "standin" (vedi Javadoc di classe). {@code xmlRpt} resta {@code null}
	 * sulla RPT ricostruita, la deserializzazione/validazione semantica va quindi saltata.
	 */
	@Test
	void ricostruisceStandinSeNonEsisteNessunaRptDelModelloPsp() {
		mockAutorizzazioneOk();
		Pendenza pendenza = new Pendenza();
		pendenza.setId(1L);
		pendenza.setIuv(IUV);
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenza));
		when(pendenzaRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(pendenza));
		when(rptRepository.findByCodDominioAndIuvAndIur(any(), any(), any())).thenReturn(Optional.empty());
		when(rptRepository.findByIdVersamentoAndStatoAndModelloPagamento(1L, "RPT_ACCETTATA_NODO",
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(Optional.empty());
		when(rptRepository.existsByIdVersamentoAndModelloPagamento(1L,
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(false);
		Rpt rptStandin = new Rpt();
		when(rptAttivazioneService.ricostruisci(eq(pendenza), any(), eq(RptAttivazioneService.VERSIONE_SANP_240),
				eq("receipt-1"), any())).thenReturn(rptStandin);

		service.eseguiSendRT(richiesta("receipt-1"));

		verify(rptAttivazioneService, never()).deserializzaDati(any());
		verify(acquisizioneRicevutaService).acquisisci(eq(pendenza), eq(rptStandin), any(DatiRicevutaPagamento.class));
	}

	/** Come {@link #ricostruisceStandinSeNonEsisteNessunaRptDelModelloPsp}, per {@code paSendRTV2}. */
	@Test
	void ricostruisceStandinV2SeNonEsisteNessunaRptDelModelloPsp() {
		mockAutorizzazioneOk();
		Pendenza pendenza = new Pendenza();
		pendenza.setId(1L);
		pendenza.setIuv(IUV);
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenza));
		when(pendenzaRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(pendenza));
		when(rptRepository.findByCodDominioAndIuvAndIur(any(), any(), any())).thenReturn(Optional.empty());
		when(rptRepository.findByIdVersamentoAndStatoAndModelloPagamento(1L, "RPT_ACCETTATA_NODO",
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(Optional.empty());
		when(rptRepository.existsByIdVersamentoAndModelloPagamento(1L,
				RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)).thenReturn(false);
		Rpt rptStandin = new Rpt();
		when(rptAttivazioneService.ricostruisci(eq(pendenza), any(), eq(RptAttivazioneService.VERSIONE_SANP_321_V2),
				eq("receipt-1"), any())).thenReturn(rptStandin);

		service.eseguiSendRTV2(richiestaV2("receipt-1"));

		verify(rptAttivazioneService, never()).deserializzaDatiV2(any());
		verify(acquisizioneRicevutaService).acquisisci(eq(pendenza), eq(rptStandin), any(DatiRicevutaPagamento.class));
	}

	private PaSendRTV2Request richiestaV2(String receiptId) {
		CtSubject debtor = new CtSubject();
		CtReceiptV2 receipt = new CtReceiptV2();
		receipt.setReceiptId(receiptId);
		receipt.setNoticeNumber("300000000000000001");
		receipt.setFiscalCode(COD_DOMINIO);
		receipt.setOutcome(StOutcome.OK);
		receipt.setCreditorReferenceId(IUV);
		receipt.setPaymentAmount(java.math.BigDecimal.TEN);
		receipt.setDescription("test");
		receipt.setCompanyName("Comune di Test");
		receipt.setDebtor(debtor);
		receipt.setIdPSP("PSP1");
		receipt.setPSPCompanyName("Psp Uno");
		receipt.setIdChannel("CH1");
		receipt.setChannelDescription("canale");

		PaSendRTV2Request request = new PaSendRTV2Request();
		request.setIdPA(COD_DOMINIO);
		request.setIdBrokerPA(ID_BROKER_PA);
		request.setIdStation("STAZIONE-TEST");
		request.setReceipt(receipt);
		return request;
	}

	private void mockAutorizzazioneOk() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario(ID_BROKER_PA)
				.principal(PRINCIPAL_REGISTRATO).abilitato(true).build();
		when(intermediarioRepository.findByCodIntermediario(ID_BROKER_PA)).thenReturn(Optional.of(intermediario));
		when(stazioneRepository.findByIntermediarioCodIntermediarioAndCodStazione(any(), any()))
				.thenReturn(Optional.of(it.govpay.common.entity.StazioneEntity.builder().codStazione("STAZIONE-TEST")
						.abilitato(true).build()));
		when(dominioRepository.findByCodDominio(COD_DOMINIO))
				.thenReturn(Optional.of(DominioEntity.builder().codDominio(COD_DOMINIO).id(1L).build()));
		autenticaCome(PRINCIPAL_REGISTRATO);
	}
}
