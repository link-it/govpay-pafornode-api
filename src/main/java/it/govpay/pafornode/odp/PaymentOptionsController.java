package it.govpay.pafornode.odp;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.pafornode.odp.api.model.PaymentOptionsResponse;
import it.govpay.pafornode.odp.api.rest.PaymentOptionsResourceApi;

/**
 * Implementa {@code getPaymentOptionsByNoticeNumber} del contratto REST "pagoPA Payment
 * Options for Organizations" (issue #4). Puramente di smistamento, stesso principio di
 * {@code PaForNodeEndpoint}: l'intera logica vive in {@link GetPaymentOptionsService}, i fault
 * applicativi ({@code NdpException}) sono tradotti in risposta HTTP da
 * {@link OdpExceptionHandler}, non qui.
 */
@RestController
public class PaymentOptionsController implements PaymentOptionsResourceApi {

	private final GetPaymentOptionsService getPaymentOptionsService;

	public PaymentOptionsController(GetPaymentOptionsService getPaymentOptionsService) {
		this.getPaymentOptionsService = getPaymentOptionsService;
	}

	@Override
	public ResponseEntity<PaymentOptionsResponse> getPaymentOptionsByNoticeNumber(String noticeNumber,
			String organizationFiscalCode) {
		PaymentOptionsResponse response = getPaymentOptionsService.eseguiGetPaymentOptions(organizationFiscalCode,
				noticeNumber);
		return ResponseEntity.ok(response);
	}
}
