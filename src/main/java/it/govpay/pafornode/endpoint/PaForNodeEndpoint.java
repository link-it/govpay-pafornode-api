package it.govpay.pafornode.endpoint;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtQrCode;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Request;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Response;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Request;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Response;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtFaultBean;
import it.gov.pagopa.pagopa_api.pa.pafornode.StOutcome;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.gde.GdeFaultContext;
import it.govpay.pafornode.rpt.PaGetPaymentActivationService;
import it.govpay.pafornode.rpt.PaGetPaymentV2ActivationService;

/**
 * Implementa {@code paVerifyPaymentNotice}, {@code paGetPayment}, {@code paGetPaymentV2},
 * {@code paSendRT} e {@code paSendRTV2} del contratto paForNode. Analisi completa del
 * legacy ({@code PagamentiTelematiciCCPImpl}) nel design doc (§1/§3/§3ter/§3quinquies/
 * §3septies/§3octies). Puramente di smistamento: l'intera logica (inclusa
 * l'attualizzazione — SEND/riacquisizione/acquisizione, §3quinquies, tutti e tre i rami
 * cablati su tutte le operazioni di attivazione/verifica) vive nei rispettivi servizi
 * transazionali ({@link PaVerifyPaymentNoticeService}/{@link PaGetPaymentActivationService}/
 * {@link PaGetPaymentV2ActivationService}/{@link PaSendRTService}), non qui — nessuno dei
 * cinque metodi e' transazionale: se lo fosse, il commit avverrebbe DOPO il ritorno del
 * metodo (quindi dopo il try/catch), e un errore al commit scapperebbe alla costruzione
 * della risposta applicativa KO, arrivando al chiamante come HTTP 500/SOAP Fault grezzo
 * invece che come {@code PAA_SYSTEM_ERROR}.
 */
@Endpoint
public class PaForNodeEndpoint {

	private static final String NAMESPACE = "http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd";

	private final PaVerifyPaymentNoticeService paVerifyPaymentNoticeService;
	private final PaGetPaymentActivationService paGetPaymentActivationService;
	private final PaGetPaymentV2ActivationService paGetPaymentV2ActivationService;
	private final PaSendRTService paSendRTService;

	public PaForNodeEndpoint(PaVerifyPaymentNoticeService paVerifyPaymentNoticeService,
			PaGetPaymentActivationService paGetPaymentActivationService,
			PaGetPaymentV2ActivationService paGetPaymentV2ActivationService, PaSendRTService paSendRTService) {
		this.paVerifyPaymentNoticeService = paVerifyPaymentNoticeService;
		this.paGetPaymentActivationService = paGetPaymentActivationService;
		this.paGetPaymentV2ActivationService = paGetPaymentV2ActivationService;
		this.paSendRTService = paSendRTService;
	}

	@PayloadRoot(namespace = NAMESPACE, localPart = "paVerifyPaymentNoticeReq")
	@ResponsePayload
	public PaVerifyPaymentNoticeRes paVerifyPaymentNotice(@RequestPayload PaVerifyPaymentNoticeReq request) {
		CtQrCode qrCode = request.getQrCode();
		String numeroAvviso = qrCode.getNoticeNumber();
		String codDominio = qrCode.getFiscalCode();
		PaVerifyPaymentNoticeRes response;
		try {
			response = paVerifyPaymentNoticeService.eseguiVerifica(request, numeroAvviso, codDominio);
		} catch (NdpException e) {
			response = buildFault(e);
		} catch (RuntimeException e) {
			response = buildFault(new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e));
		}
		return response;
	}

	/**
	 * A differenza di {@code paVerifyPaymentNotice} (sola verifica), attiva una RPT:
	 * costruisce il {@code CtPaymentPA}, la persiste (annullando eventuali RPT pendenti sulla
	 * stessa coppia dominio/iuv) e accoda la notifica di attivazione all'applicazione.
	 * Rifiuta a monte una pendenza con marca da bollo telematica (non ammessa su questa
	 * primitiva, a differenza di {@code paGetPaymentV2}).
	 *
	 * <p>Non transazionale: l'intera operazione (verifica + attivazione) e' transazionale
	 * dentro {@link PaGetPaymentActivationService}, cosi' il commit avviene PRIMA che questo
	 * metodo decida la risposta — un errore al commit va quindi nel catch sotto, non scappa
	 * come SOAP Fault grezzo dopo che la risposta era gia' stata costruita.</p>
	 */
	@PayloadRoot(namespace = NAMESPACE, localPart = "paGetPaymentReq")
	@ResponsePayload
	public PaGetPaymentRes paGetPayment(@RequestPayload PaGetPaymentReq request) {
		CtQrCode qrCode = request.getQrCode();
		String numeroAvviso = qrCode.getNoticeNumber();
		String codDominio = qrCode.getFiscalCode();
		PaGetPaymentRes response;
		try {
			response = paGetPaymentActivationService.eseguiGetPayment(request, numeroAvviso, codDominio);
		} catch (NdpException e) {
			response = buildFaultGetPayment(e);
		} catch (RuntimeException e) {
			response = buildFaultGetPayment(new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e));
		}
		return response;
	}

	private static PaGetPaymentRes buildFaultGetPayment(NdpException e) {
		GdeFaultContext.segnalaFault(e.getFault().name(), e.getDescrizione());
		PaGetPaymentRes response = new PaGetPaymentRes();
		response.setOutcome(StOutcome.KO);
		CtFaultBean fault = new CtFaultBean();
		fault.setId(e.getCodDominio());
		fault.setFaultCode(e.getFault().name());
		fault.setFaultString(e.getFault().getFaultString());
		fault.setDescription(e.getDescrizione());
		response.setFault(fault);
		return response;
	}

	/**
	 * Stessa struttura di {@link #paGetPayment}: non transazionale, l'intera operazione e'
	 * dentro {@link PaGetPaymentV2ActivationService}. A differenza di {@link #paGetPayment},
	 * non rifiuta a monte la marca da bollo telematica — la gestisce {@code
	 * CtPaymentPaV2Builder} per singolo transfer.
	 */
	@PayloadRoot(namespace = NAMESPACE, localPart = "paGetPaymentV2Request")
	@ResponsePayload
	public PaGetPaymentV2Response paGetPaymentV2(@RequestPayload PaGetPaymentV2Request request) {
		CtQrCode qrCode = request.getQrCode();
		String numeroAvviso = qrCode.getNoticeNumber();
		String codDominio = qrCode.getFiscalCode();
		PaGetPaymentV2Response response;
		try {
			response = paGetPaymentV2ActivationService.eseguiGetPaymentV2(request, numeroAvviso, codDominio);
		} catch (NdpException e) {
			response = buildFaultGetPaymentV2(e);
		} catch (RuntimeException e) {
			response = buildFaultGetPaymentV2(new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e));
		}
		return response;
	}

	private static PaGetPaymentV2Response buildFaultGetPaymentV2(NdpException e) {
		GdeFaultContext.segnalaFault(e.getFault().name(), e.getDescrizione());
		PaGetPaymentV2Response response = new PaGetPaymentV2Response();
		response.setOutcome(StOutcome.KO);
		CtFaultBean fault = new CtFaultBean();
		fault.setId(e.getCodDominio());
		fault.setFaultCode(e.getFault().name());
		fault.setFaultString(e.getFault().getFaultString());
		fault.setDescription(e.getDescrizione());
		response.setFault(fault);
		return response;
	}

	/**
	 * Stessa struttura delle altre quattro operazioni: non transazionale, l'intera
	 * acquisizione della ricevuta e' dentro {@link PaSendRTService}. A differenza di
	 * {@code paVerifyPaymentNotice}/{@code paGetPayment}, IUV e dominio si leggono
	 * direttamente da {@code CtReceipt}, non da un {@code CtQrCode}.
	 *
	 * <p><b>{@code DataIntegrityViolationException} intercettata qui, non in {@code
	 * PaSendRTService}</b> (bug di revisione corretto, 2026-11-04): e' la violazione del
	 * vincolo unique su {@code rpt} per una ricevuta senza pendenza in race con un'altra
	 * (stesso {@code receiptId}, issue #6) — mappata su {@code PAA_RECEIPT_DUPLICATA}
	 * invece di cadere nel generico {@code PAA_SYSTEM_ERROR} del catch sotto. Va presa qui,
	 * DOPO che la transazione di {@code eseguiSendRT} ha gia' fatto un rollback normale (non
	 * c'era nulla da preservare): catturarla dentro il service, che partecipa alla stessa
	 * transazione del chiamante, produrrebbe {@code UnexpectedRollbackException} al commit
	 * invece del fault (vedi Javadoc di {@code PaSendRTService#gestisciRicevutaSenzaPendenza}).
	 * </p>
	 *
	 * <p><b>Riconosce specificamente il vincolo di {@code rpt}</b> (bug di revisione
	 * corretto, 2026-11-04): {@link #vincolaRptGiaAcquisita} verifica che sia proprio
	 * {@code unique_rpt_id_transazione} a essere violato — una violazione di FK, NOT NULL o
	 * di un altro vincolo non significa che la ricevuta sia gia' stata acquisita, e andrebbe
	 * comunque nel generico {@code PAA_SYSTEM_ERROR}, non in {@code PAA_RECEIPT_DUPLICATA}.
	 * </p>
	 */
	@PayloadRoot(namespace = NAMESPACE, localPart = "paSendRTReq")
	@ResponsePayload
	public PaSendRTRes paSendRT(@RequestPayload PaSendRTReq request) {
		String codDominio = request.getReceipt().getFiscalCode();
		PaSendRTRes response;
		try {
			response = paSendRTService.eseguiSendRT(request);
		} catch (NdpException e) {
			response = buildFaultSendRT(e);
		} catch (DataIntegrityViolationException e) {
			response = buildFaultSendRT(vincolaRptGiaAcquisita(e)
					? new NdpException(FaultPa.PAA_RECEIPT_DUPLICATA, e.getMessage(), codDominio, e)
					: new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e));
		} catch (RuntimeException e) {
			response = buildFaultSendRT(new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e));
		}
		return response;
	}

	/**
	 * {@code true} solo se la violazione e' proprio sul vincolo unique
	 * {@code unique_rpt_id_transazione} ({@code iuv}, {@code ccp}/{@code receiptId},
	 * {@code cod_dominio}) — il solo caso che significhi "ricevuta gia' acquisita" per
	 * questo contratto. Il nome del vincolo compare testualmente nel messaggio della causa
	 * piu' specifica sia su H2 (usato nei test) sia su PostgreSQL (produzione): non serve
	 * ispezionare il dialetto. Qualunque altra {@code DataIntegrityViolationException} (FK,
	 * NOT NULL, un vincolo diverso) NON significa duplicato — resta {@code
	 * PAA_SYSTEM_ERROR}.
	 */
	private static boolean vincolaRptGiaAcquisita(DataIntegrityViolationException e) {
		String messaggio = e.getMostSpecificCause().getMessage();
		return messaggio != null && messaggio.toLowerCase().contains("unique_rpt_id_transazione");
	}

	private static PaSendRTRes buildFaultSendRT(NdpException e) {
		GdeFaultContext.segnalaFault(e.getFault().name(), e.getDescrizione());
		PaSendRTRes response = new PaSendRTRes();
		response.setOutcome(StOutcome.KO);
		CtFaultBean fault = new CtFaultBean();
		fault.setId(e.getCodDominio());
		fault.setFaultCode(e.getFault().name());
		fault.setFaultString(e.getFault().getFaultString());
		fault.setDescription(e.getDescrizione());
		response.setFault(fault);
		return response;
	}

	/** Come {@link #paSendRT}, per {@code paSendRTV2} — stesso motivo per il catch dedicato. */
	@PayloadRoot(namespace = NAMESPACE, localPart = "paSendRTV2Request")
	@ResponsePayload
	public PaSendRTV2Response paSendRTV2(@RequestPayload PaSendRTV2Request request) {
		String codDominio = request.getReceipt().getFiscalCode();
		PaSendRTV2Response response;
		try {
			response = paSendRTService.eseguiSendRTV2(request);
		} catch (NdpException e) {
			response = buildFaultSendRTV2(e);
		} catch (DataIntegrityViolationException e) {
			response = buildFaultSendRTV2(vincolaRptGiaAcquisita(e)
					? new NdpException(FaultPa.PAA_RECEIPT_DUPLICATA, e.getMessage(), codDominio, e)
					: new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e));
		} catch (RuntimeException e) {
			response = buildFaultSendRTV2(new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e));
		}
		return response;
	}

	private static PaSendRTV2Response buildFaultSendRTV2(NdpException e) {
		GdeFaultContext.segnalaFault(e.getFault().name(), e.getDescrizione());
		PaSendRTV2Response response = new PaSendRTV2Response();
		response.setOutcome(StOutcome.KO);
		CtFaultBean fault = new CtFaultBean();
		fault.setId(e.getCodDominio());
		fault.setFaultCode(e.getFault().name());
		fault.setFaultString(e.getFault().getFaultString());
		fault.setDescription(e.getDescrizione());
		response.setFault(fault);
		return response;
	}

	private static PaVerifyPaymentNoticeRes buildFault(NdpException e) {
		GdeFaultContext.segnalaFault(e.getFault().name(), e.getDescrizione());
		PaVerifyPaymentNoticeRes response = new PaVerifyPaymentNoticeRes();
		response.setOutcome(StOutcome.KO);
		CtFaultBean fault = new CtFaultBean();
		fault.setId(e.getCodDominio());
		fault.setFaultCode(e.getFault().name());
		fault.setFaultString(e.getFault().getFaultString());
		fault.setDescription(e.getDescrizione());
		response.setFault(fault);
		return response;
	}
}
