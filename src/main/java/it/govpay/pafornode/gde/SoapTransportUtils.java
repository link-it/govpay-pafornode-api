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
 * GdeEventFilter} tramite attributi di richiesta — e, da issue #10, da
 * {@code it.govpay.pafornode.endpoint.PaSendRTService} per distinguere il canale
 * Nodo/interno (via {@code AuthTypeAccessor}, non serve un attributo di richiesta dedicato).
 */
public final class SoapTransportUtils {

    /**
     * Path esatto del canale interno (issue #10), mappato in {@code it.govpay.pafornode
     * .config.WsConfig#messageDispatcherServlet} sullo stesso servlet del canale Nodo
     * ({@code /ws/*}, wildcard). Pubblico: {@code WsConfig} registra il servlet su questo
     * stesso valore, cosi' i due posti non possono disallinearsi.
     */
    public static final String PATH_CANALE_INTERNO = "/internal/PagamentiTelematiciCCPservice";

    private SoapTransportUtils() {
    }

    public static HttpServletRequest currentHttpServletRequest() {
        TransportContext transportContext = TransportContextHolder.getTransportContext();
        if (transportContext != null
                && transportContext.getConnection() instanceof HttpServletConnection connection) {
            return connection.getHttpServletRequest();
        }
        return null;
    }

    /**
     * Unica definizione di "canale interno per path" (issue #10) — usata sia da {@link
     * it.govpay.pafornode.endpoint.PaSendRTService#verificaCanale} (autorizzazione: il path
     * deve corrispondere all'{@code AuthType} usato) sia da {@link GdeEventFilter}
     * (l'operatore {@code GOVPAY-ON-BEHALF-OF} ha senso solo su questo canale) sia dalle
     * altre operazioni del canale Nodo (che su questo path non devono essere raggiungibili
     * affatto, qualunque sia l'autenticazione — vedi {@code PaForNodeChecks
     * #verificaCanaleNodo}).
     *
     * <p>Bug di revisione corretto 2026-11-05: confrontava {@code request.getRequestURI()}
     * con un prefisso ({@code .contains("/internal/")}), aggirabile sfruttando il mapping a
     * wildcard del canale Nodo ({@code /ws/*}, che Spring accetta per QUALUNQUE sotto-path,
     * incluso {@code /ws/internal/qualcosa}) — una simile richiesta risultava classificata
     * come canale interno pur essendo arrivata (ed essendo stata autenticata) dal lato Nodo.
     * Si confronta invece {@link HttpServletRequest#getServletPath()} — il path EFFETTIVO
     * del servizio che ha gestito la richiesta, al netto del context path e della query
     * string — con l'esatto path registrato per il canale interno: per il mapping a
     * wildcard del Nodo questo valore e' sempre {@code /ws} (il prefisso statico), mai il
     * path interno, indipendentemente da cosa segue nell'URL.
     */
    public static boolean isPathCanaleInterno(HttpServletRequest request) {
        return request != null && PATH_CANALE_INTERNO.equals(request.getServletPath());
    }
}
