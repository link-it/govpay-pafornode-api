package it.govpay.pafornode.gde;

import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Recupera l'{@link HttpServletRequest} corrente dentro {@code MessageDispatcherServlet} —
 * non è un parametro diretto per un {@code @Endpoint}/{@code EndpointInterceptor}, si legge
 * dal {@link TransportContextHolder}, lo stesso meccanismo con cui Spring-WS espone la
 * connessione HTTP corrente. Condiviso da {@link OperationIdEndpointInterceptor} e
 * {@link GdeFaultContext}, entrambi punti che devono propagare informazioni verso {@link
 * GdeEventFilter} tramite attributi di richiesta.
 */
final class SoapTransportUtils {

    private SoapTransportUtils() {
    }

    static HttpServletRequest currentHttpServletRequest() {
        TransportContext transportContext = TransportContextHolder.getTransportContext();
        if (transportContext != null
                && transportContext.getConnection() instanceof HttpServletConnection connection) {
            return connection.getHttpServletRequest();
        }
        return null;
    }
}
