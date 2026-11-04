package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceipt;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Request;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.rpt.PaGetPaymentActivationService;
import it.govpay.pafornode.rpt.PaGetPaymentV2ActivationService;

/**
 * Unit test diretto su {@link PaForNodeEndpoint} (nessun contesto Spring, nessuna chiamata
 * HTTP): verifica la mappatura dedicata di {@code DataIntegrityViolationException} su {@code
 * PAA_RECEIPT_DUPLICATA} per {@code paSendRT}/{@code paSendRTV2} — bug di revisione corretto,
 * 2026-11-04. Deve avvenire qui, non dentro {@code PaSendRTService}: quel service e'
 * {@code @Transactional} e, se catturasse l'eccezione internamente, lo farebbe DOPO che la
 * transazione e' gia' stata marcata rollback-only dal proxy Spring attorno a {@code
 * RptAttivazioneService#creaOrfana} (anch'esso {@code @Transactional}, partecipa alla stessa
 * transazione) — un tentativo di commit a quel punto produce {@code
 * UnexpectedRollbackException}, non il fault voluto. Qui, quando il test simula l'eccezione
 * uscendo gia' da {@code PaSendRTService} (mockato), nessuna transazione e' piu' in corso:
 * la mappatura e' sicura.
 *
 * <p>Una vera race a due thread non e' riproducibile deterministicamente in un test — questo
 * verifica solo la mappatura della risposta, non la transazione reale (coperta dall'analisi
 * sopra, non da un test automatico).</p>
 */
class PaForNodeEndpointFaultMappingTest {

	private static final String COD_DOMINIO = "12345678901";

	private final PaVerifyPaymentNoticeService paVerifyPaymentNoticeService = mock(
			PaVerifyPaymentNoticeService.class);
	private final PaGetPaymentActivationService paGetPaymentActivationService = mock(
			PaGetPaymentActivationService.class);
	private final PaGetPaymentV2ActivationService paGetPaymentV2ActivationService = mock(
			PaGetPaymentV2ActivationService.class);
	private final PaSendRTService paSendRTService = mock(PaSendRTService.class);

	private final PaForNodeEndpoint endpoint = new PaForNodeEndpoint(paVerifyPaymentNoticeService,
			paGetPaymentActivationService, paGetPaymentV2ActivationService, paSendRTService);

	@Test
	void paSendRTMappaLaViolazioneDelVincoloSuReceiptDuplicata() {
		when(paSendRTService.eseguiSendRT(any())).thenThrow(new DataIntegrityViolationException("unique_rpt_id_transazione"));

		var risposta = endpoint.paSendRT(richiestaSendRT()).getValue();

		assertThat(risposta.getFault().getFaultCode()).isEqualTo(FaultPa.PAA_RECEIPT_DUPLICATA.name());
	}

	@Test
	void paSendRTV2MappaLaViolazioneDelVincoloSuReceiptDuplicata() {
		when(paSendRTService.eseguiSendRTV2(any()))
				.thenThrow(new DataIntegrityViolationException("unique_rpt_id_transazione"));

		var risposta = endpoint.paSendRTV2(richiestaSendRTV2()).getValue();

		assertThat(risposta.getFault().getFaultCode()).isEqualTo(FaultPa.PAA_RECEIPT_DUPLICATA.name());
	}

	/**
	 * Bug di revisione corretto, 2026-11-04: una {@code DataIntegrityViolationException} su
	 * un vincolo DIVERSO da {@code unique_rpt_id_transazione} (qui una FK, per esempio) NON
	 * significa "ricevuta gia' acquisita" — deve restare {@code PAA_SYSTEM_ERROR}, non
	 * {@code PAA_RECEIPT_DUPLICATA}.
	 */
	@Test
	void paSendRTNonMappaSuReceiptDuplicataUnVincoloDiverso() {
		when(paSendRTService.eseguiSendRT(any()))
				.thenThrow(new DataIntegrityViolationException("fk_pag_id_rpt violated"));

		var risposta = endpoint.paSendRT(richiestaSendRT()).getValue();

		assertThat(risposta.getFault().getFaultCode()).isEqualTo(FaultPa.PAA_SYSTEM_ERROR.name());
	}

	/** Come {@link #paSendRTNonMappaSuReceiptDuplicataUnVincoloDiverso}, per V2. */
	@Test
	void paSendRTV2NonMappaSuReceiptDuplicataUnVincoloDiverso() {
		when(paSendRTService.eseguiSendRTV2(any()))
				.thenThrow(new DataIntegrityViolationException("fk_pag_id_rpt violated"));

		var risposta = endpoint.paSendRTV2(richiestaSendRTV2()).getValue();

		assertThat(risposta.getFault().getFaultCode()).isEqualTo(FaultPa.PAA_SYSTEM_ERROR.name());
	}

	private PaSendRTReq richiestaSendRT() {
		CtReceipt receipt = new CtReceipt();
		receipt.setFiscalCode(COD_DOMINIO);
		receipt.setCreditorReferenceId("300000000000000001");
		PaSendRTReq request = new PaSendRTReq();
		request.setReceipt(receipt);
		return request;
	}

	private PaSendRTV2Request richiestaSendRTV2() {
		CtReceiptV2 receipt = new CtReceiptV2();
		receipt.setFiscalCode(COD_DOMINIO);
		receipt.setCreditorReferenceId("300000000000000001");
		PaSendRTV2Request request = new PaSendRTV2Request();
		request.setReceipt(receipt);
		return request;
	}
}
