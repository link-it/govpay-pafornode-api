package it.govpay.pafornode.config;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.soap.SoapMessageCreationException;
import org.springframework.ws.transport.http.MessageDispatcherServlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@link MessageDispatcherServlet} che risponde con un errore del client, invece di 500, quando
 * la richiesta non e' un messaggio SOAP.
 *
 * <p>Spring-WS risponde gia' 400 a un XML malformato ({@code InvalidXmlException}) e 405 ai
 * metodi diversi da POST, ma lascia propagare la {@link SoapMessageCreationException} che SAAJ
 * solleva per un Content-Type non SOAP o per un XML ben formato senza {@code Envelope}: il
 * container la traduce in 500. Qui diventa 415 se il Content-Type non e' SOAP
 * ({@code text/xml} per SOAP 1.1, {@code application/soap+xml} per SOAP 1.2), 400 altrimenti.</p>
 */
public class PaForNodeMessageDispatcherServlet extends MessageDispatcherServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger LOG = LoggerFactory.getLogger(PaForNodeMessageDispatcherServlet.class);

	@Override
	protected void doService(HttpServletRequest request, HttpServletResponse response) throws Exception {
		try {
			super.doService(request, response);
		}
		catch (SoapMessageCreationException ex) {
			if (response.isCommitted()) {
				throw ex;
			}
			int status = isContentTypeSoap(request.getContentType()) ? HttpServletResponse.SC_BAD_REQUEST
					: HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE;
			LOG.warn("Richiesta non SOAP su {} (Content-Type {}): risposta {}. {}", request.getRequestURI(),
					request.getContentType(), status, ex.getMessage());
			response.setStatus(status);
		}
	}

	static boolean isContentTypeSoap(String contentType) {
		if (contentType == null) {
			return false;
		}
		String tipo = contentType.toLowerCase(Locale.ROOT);
		return tipo.startsWith("text/xml") || tipo.startsWith("application/soap+xml");
	}
}
