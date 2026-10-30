package it.govpay.pafornode.gde;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.MethodEndpoint;
import org.springframework.ws.server.endpoint.interceptor.EndpointInterceptorAdapter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Rende disponibili, come request attribute, due informazioni che {@link GdeEventFilter}
 * legge solo dopo l'esecuzione della catena (nel proprio {@code finally}):
 * <ul>
 *   <li>il nome dell'operazione (metodo Java dell'endpoint, coincidente con il nome
 *       dell'operazione SOAP paForNode) — un {@link jakarta.servlet.Filter} non riceve mai
 *       l'endpoint risolto da Spring-WS, solo un {@link org.springframework.ws.server.EndpointInterceptor}
 *       lo espone, in {@link #handleRequest};</li>
 *   <li>il principal autenticato — deve essere letto QUI, non nel {@code finally} del
 *       filtro: la catena di Spring Security ripulisce il {@code SecurityContext} nel proprio
 *       {@code finally}, che si chiude prima di risalire fino a un filtro esterno come
 *       {@link GdeEventFilter} (ordine {@code HIGHEST_PRECEDENCE + 120}, ampiamente esterno
 *       alla security chain). Questo interceptor gira alla stessa profondità
 *       dell'endpoint, dentro {@code MessageDispatcherServlet}, quando il contesto è ancora
 *       garantito popolato — stessa profondità a cui un {@code HandlerInterceptor} di Spring
 *       MVC girerebbe per un controller REST.</li>
 * </ul>
 * <p>
 * L'{@link HttpServletRequest} sottostante non è un parametro di {@code handleRequest}
 * (a differenza di un {@code HandlerInterceptor}): si recupera dal
 * {@link TransportContextHolder}, lo stesso meccanismo con cui Spring-WS espone la
 * connessione HTTP corrente agli endpoint/interceptor.
 */
@Component
public class OperationIdEndpointInterceptor extends EndpointInterceptorAdapter {

    public static final String OPERATION_ID_ATTRIBUTE = "it.govpay.pafornode.gde.operationId";
    public static final String PRINCIPAL_ATTRIBUTE = "it.govpay.pafornode.gde.principal";

    @Override
    public boolean handleRequest(MessageContext messageContext, Object endpoint) {
        HttpServletRequest request = SoapTransportUtils.currentHttpServletRequest();
        if (request == null) {
            return true;
        }
        if (endpoint instanceof MethodEndpoint methodEndpoint) {
            request.setAttribute(OPERATION_ID_ATTRIBUTE, methodEndpoint.getMethod().getName());
        }
        String principal = currentPrincipal();
        if (principal != null) {
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        }
        return true;
    }

    private static String currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }
}
