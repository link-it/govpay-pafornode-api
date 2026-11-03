package it.govpay.pafornode.odp;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.gde.GdeFaultContext;
import it.govpay.pafornode.odp.OdpFaultMapper.Esito;
import it.govpay.pafornode.odp.api.model.ErrorResponse;

/**
 * Traduce {@link NdpException} (lanciata da {@link GetPaymentOptionsService}, stessi
 * {@link FaultPa} usati da {@code paVerifyPaymentNotice}/{@code paGetPayment}) nel corpo
 * {@code ErrorResponse} del contratto REST "pagoPA Payment Options for Organizations" —
 * stesso principio di {@code PaForNodeEndpoint#buildFault*}, ma qui il fault e' la risposta
 * HTTP stessa (status + body), non una busta sempre-200.
 *
 * <p>Solo su questo controller, non globale: {@code @RestControllerAdvice} senza
 * {@code assignableTypes}/{@code basePackages} si applicherebbe a qualunque futuro
 * controller REST di questo modulo — oggi ce n'e' uno solo, ma la segnalazione del fault al
 * GDE ({@link GdeFaultContext#segnalaFault}) e' specifica del contratto paForNode, non un
 * comportamento da estendere implicitamente.</p>
 */
@RestControllerAdvice(assignableTypes = PaymentOptionsController.class)
class OdpExceptionHandler {

	private final Clock clock;

	OdpExceptionHandler(Clock clock) {
		this.clock = clock;
	}

	@ExceptionHandler(NdpException.class)
	ResponseEntity<ErrorResponse> handleNdpException(NdpException e) {
		GdeFaultContext.segnalaFault(e.getFault().name(), e.getDescrizione());
		Esito esito = OdpFaultMapper.risolvi(e.getFault());
		return build(esito, e.getFault(), e.getDescrizione());
	}

	@ExceptionHandler(RuntimeException.class)
	ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException e) {
		GdeFaultContext.segnalaFault(FaultPa.PAA_SYSTEM_ERROR.name(), e.getMessage());
		return build(OdpFaultMapper.risolvi(FaultPa.PAA_SYSTEM_ERROR), FaultPa.PAA_SYSTEM_ERROR, e.getMessage());
	}

	private ResponseEntity<ErrorResponse> build(Esito esito, FaultPa fault, String descrizione) {
		Instant adesso = clock.instant();
		String messaggio = descrizione != null ? fault.name() + " " + descrizione : fault.getFaultString();
		ErrorResponse body = new ErrorResponse().httpStatusCode(esito.status().value())
				.httpStatusDescription(esito.status().getReasonPhrase()).appErrorCode(esito.codiceOdp())
				.errorMessage(messaggio).timestamp(adesso.getEpochSecond())
				.dateTime(OffsetDateTime.ofInstant(adesso, clock.getZone()).toString());
		return ResponseEntity.status(esito.status()).body(body);
	}
}
