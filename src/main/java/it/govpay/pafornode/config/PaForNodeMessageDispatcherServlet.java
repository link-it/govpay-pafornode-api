package it.govpay.pafornode.config;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.soap.SoapMessageCreationException;
import org.springframework.ws.transport.http.HttpTransportConstants;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.WsdlDefinition;
import org.springframework.ws.wsdl.wsdl11.Wsdl11Definition;

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
 *
 * <p>Issue #10, finding #4 di revisione (2026-11-05): {@code MessageDispatcherServlet} pubblica
 * il WSDL SOLO con la convenzione a suo nome ({@code <path>/<nomeBean>.wsdl} — qui {@code
 * /ws/paForNode.wsdl}, hardcoded in {@code MessageDispatcherServlet#getWsdlDefinition}, verificato
 * sui sorgenti della libreria: confronta solo il suffisso {@code .wsdl} del path, nessun supporto
 * nativo a {@code ?wsdl}). pagoPA/l'issue richiedono invece la convenzione {@code ?wsdl} sulla
 * STESSA URL del servizio (es. {@code GET /PagamentiTelematiciCCPservice?wsdl}). Qui si aggiunge
 * SENZA toccare la convenzione esistente (restituita prima, se applicabile): se il metodo e' GET
 * e il parametro {@code wsdl} e' presente, si restituisce l'UNICO bean {@link Wsdl11Definition}
 * dell'applicazione (un solo contratto pubblicato, su entrambi i canali).</p>
 */
public class PaForNodeMessageDispatcherServlet extends MessageDispatcherServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger LOG = LoggerFactory.getLogger(PaForNodeMessageDispatcherServlet.class);

	private static final String WSDL_QUERY_PARAM = "wsdl";

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

	@Override
	protected WsdlDefinition getWsdlDefinition(HttpServletRequest request) {
		WsdlDefinition viaConvenzioneSulNomeDelBean = super.getWsdlDefinition(request);
		if (viaConvenzioneSulNomeDelBean != null) {
			return viaConvenzioneSulNomeDelBean;
		}
		if (HttpTransportConstants.METHOD_GET.equals(request.getMethod())
				&& request.getParameter(WSDL_QUERY_PARAM) != null) {
			return getWebApplicationContext().getBean(Wsdl11Definition.class);
		}
		return null;
	}

	static boolean isContentTypeSoap(String contentType) {
		if (contentType == null) {
			return false;
		}
		String tipo = contentType.toLowerCase(Locale.ROOT);
		return tipo.startsWith("text/xml") || tipo.startsWith("application/soap+xml");
	}
}
