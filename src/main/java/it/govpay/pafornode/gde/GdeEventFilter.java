package it.govpay.pafornode.gde;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import it.govpay.common.gde.GdeEventInfo;
import it.govpay.common.gde.GdeUtils;
import it.govpay.common.logging.TransactionContext;
import it.govpay.gde.client.beans.CategoriaEvento;
import it.govpay.gde.client.beans.ComponenteEvento;
import it.govpay.gde.client.beans.EsitoEvento;
import it.govpay.gde.client.beans.Header;
import it.govpay.gde.client.beans.RuoloEvento;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Genera un evento GDE per ogni richiesta SOAP (componente {@link ComponenteEvento#API_PAGOPA},
 * categoria {@link CategoriaEvento#INTERFACCIA}, ruolo {@link RuoloEvento#SERVER}).
 * <p>
 * Il perimetro è tutto {@code /ws/**} (incluso il WSDL): è la policy configurata su GDE
 * (Giornale, log/dump SEMPRE/MAI/SOLO_ERRORE) a decidere cosa viene effettivamente
 * registrato — {@link PaForNodeGdeService} delega questa valutazione ad
 * {@link it.govpay.common.gde.AbstractGdeService#inviaEvento}, che per {@code API_PAGOPA}
 * classifica lettura/scrittura dal nome dell'operazione SOAP ({@link GdeUtils#isRequestLettura
 * (String, ComponenteEvento, String)}), non dal metodo HTTP (sempre POST).
 * <p>
 * Cattura il body di richiesta e risposta solo se il Content-Type è testuale/strutturato
 * (JSON, XML, text/*, quindi anche {@code text/xml} usato dal binding SOAP 1.1 di questo
 * contratto); altrimenti sostituisce il payload con il placeholder fisso {@code "binary"},
 * senza mai bufferizzare l'intero contenuto binario in memoria (vedi
 * {@link GdeCapturingResponseWrapper}). Gli header sensibili (Authorization, Cookie, ecc.)
 * sono oscurati da {@link GdeUtils#maskSensitiveHeaderValue}.
 * <p>
 * L'esito dell'evento NON si classifica dal solo stato HTTP: per il contratto paForNode un
 * fault applicativo (incluso {@code PAA_SYSTEM_ERROR}) è sempre restituito con HTTP 200
 * (risposta normale con {@code outcome=KO} nel body, mai un SOAP Fault né uno status di
 * errore — {@code PaForNodeEndpoint} cattura sempre le eccezioni internamente). {@link
 * GdeFaultContext#segnalaFault}, chiamato da {@code PaForNodeEndpoint} quando costruisce una
 * risposta di fault, ha quindi precedenza sullo stato HTTP — quest'ultimo resta comunque il
 * valore REALE riportato in {@code statusCodeRisposta} (sempre 200 per queste 5 operazioni),
 * i due concetti restano separati.
 * <p>
 * L'ordine deve essere numericamente MAGGIORE dell'ordine di {@code TransactionIdFilter}
 * (default {@code HIGHEST_PRECEDENCE + 100}), non minore: un filtro con ordine più basso
 * avvolge quelli con ordine più alto dall'esterno, quindi il suo blocco {@code finally} (dove
 * questo filtro costruisce l'evento GDE, incluso {@code transactionId}) verrebbe eseguito DOPO
 * che {@code TransactionIdFilter} ha già ripulito il proprio contesto — qui {@code
 * TransactionContext.getTransactionId()} risulterebbe già nullo. Resta comunque ampiamente
 * esterno alla catena di sicurezza di Spring (ordinata a {@code SecurityProperties
 * .DEFAULT_FILTER_ORDER}, un valore prossimo allo zero, non a {@code HIGHEST_PRECEDENCE}):
 * nessun conflitto tra i due vincoli.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 120)
public class GdeEventFilter extends OncePerRequestFilter {

    private static final String UNKNOWN_OPERATION = "UNKNOWN";
    private static final String BINARY_PLACEHOLDER = "binary";
    private static final int MAX_CAPTURE_BYTES = 65536;

    private final PaForNodeGdeService paForNodeGdeService;
    private final Clock clock;

    public GdeEventFilter(PaForNodeGdeService paForNodeGdeService, Clock clock) {
        this.paForNodeGdeService = paForNodeGdeService;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (DispatcherType.ERROR.equals(request.getDispatcherType())) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/actuator");
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        OffsetDateTime start = OffsetDateTime.now(clock);
        // La durata si misura sul clock monotono: OffsetDateTime.now() e' un orologio
        // di parete, soggetto a correzioni NTP, e puo' produrre durate negative.
        long startNanos = System.nanoTime();
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, MAX_CAPTURE_BYTES);
        GdeCapturingResponseWrapper wrappedResponse = new GdeCapturingResponseWrapper(response, MAX_CAPTURE_BYTES);
        Throwable error = null;

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } catch (IOException | ServletException | RuntimeException e) {
            error = e;
            throw e;
        } finally {
            long durataMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            buildAndSendEvent(wrappedRequest, wrappedResponse, start, durataMs, error);
        }
    }

    private void buildAndSendEvent(ContentCachingRequestWrapper request, GdeCapturingResponseWrapper response,
                                   OffsetDateTime start, long durataMs, Throwable error) {
        int status = status(response, error);
        String faultCode = (String) request.getAttribute(GdeFaultContext.FAULT_CODE_ATTRIBUTE);
        EsitoEvento esito = faultCode != null ? EsitoEvento.KO : esitoDaStatus(status);

        GdeEventInfo eventInfo = GdeEventInfo.builder()
                .componente(ComponenteEvento.API_PAGOPA)
                .categoriaEvento(CategoriaEvento.INTERFACCIA)
                .ruolo(RuoloEvento.SERVER)
                .tipoEvento(operationId(request))
                .dataEvento(start)
                .durataEvento(durataMs)
                .esito(esito)
                .descrizioneEsito(descrizioneEsito(request, error, faultCode))
                .principal(currentPrincipal(request))
                .urlRichiesta(fullUrl(request))
                .metodoHttp(request.getMethod())
                .headersRichiesta(extractRequestHeaders(request))
                .payloadRichiesta(requestPayload(request))
                .statusCodeRisposta(status)
                .headersRisposta(extractResponseHeaders(response))
                .payloadRisposta(responsePayload(response))
                .transactionId(TransactionContext.getTransactionId())
                .build();

        paForNodeGdeService.inviaEventoAsync(eventInfo);
    }

    /**
     * Quando l'esito applicativo è un fault (propagato da {@link GdeFaultContext}), la
     * descrizione riporta codice e descrizione del fault pagoPA — altrimenti, se la chain ha
     * propagato un'eccezione, il suo messaggio; nessuno dei due casi, nessuna descrizione.
     */
    private static String descrizioneEsito(HttpServletRequest request, Throwable error, String faultCode) {
        if (error != null) {
            return error.getMessage();
        }
        if (faultCode != null) {
            String descrizioneFault = (String) request.getAttribute(GdeFaultContext.FAULT_DESCRIZIONE_ATTRIBUTE);
            return descrizioneFault != null ? faultCode + ": " + descrizioneFault : faultCode;
        }
        return null;
    }

    /**
     * Se la chain ha propagato un'eccezione senza che nessuno abbia impostato uno status di
     * errore sulla response (es. eccezione non gestita da nessun exception resolver), lo
     * status resterebbe 200 di default e l'evento uscirebbe erroneamente come OK. Fallback
     * per le richieste che NON passano da {@code PaForNodeEndpoint} (es. WSDL, autenticazione
     * fallita prima del dispatch): per le 5 operazioni paForNode l'esito applicativo
     * (sopra, da {@link GdeFaultContext}) ha sempre precedenza, perché per questo contratto
     * un fault è sempre HTTP 200.
     */
    private static EsitoEvento esitoDaStatus(int status) {
        if (status < 400) {
            return EsitoEvento.OK;
        }
        return status >= 500 ? EsitoEvento.FAIL : EsitoEvento.KO;
    }

    private static int status(HttpServletResponse response, Throwable error) {
        int status = response.getStatus();
        if (error != null && status < 400) {
            return 500;
        }
        return status;
    }

    private static String operationId(HttpServletRequest request) {
        Object attr = request.getAttribute(OperationIdEndpointInterceptor.OPERATION_ID_ATTRIBUTE);
        return attr != null ? attr.toString() : UNKNOWN_OPERATION;
    }

    /**
     * Letto dall'attributo valorizzato da {@link OperationIdEndpointInterceptor}, non da
     * {@code SecurityContextHolder} direttamente: nel {@code finally} di questo filtro il
     * {@code SecurityContext} è già stato ripulito dalla security chain (vedi Javadoc di
     * {@link OperationIdEndpointInterceptor}).
     */
    private static String currentPrincipal(HttpServletRequest request) {
        Object attr = request.getAttribute(OperationIdEndpointInterceptor.PRINCIPAL_ATTRIBUTE);
        return attr != null ? attr.toString() : null;
    }

    private static String fullUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder(request.getRequestURL());
        if (request.getQueryString() != null) {
            url.append('?').append(request.getQueryString());
        }
        return url.toString();
    }

    private static List<Header> extractRequestHeaders(HttpServletRequest request) {
        List<Header> headers = new ArrayList<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.add(header(name, request.getHeader(name)));
            }
        }
        return headers;
    }

    private static List<Header> extractResponseHeaders(HttpServletResponse response) {
        List<Header> headers = new ArrayList<>();
        for (String name : response.getHeaderNames()) {
            headers.add(header(name, response.getHeader(name)));
        }
        return headers;
    }

    private static Header header(String nome, String valore) {
        Header header = new Header();
        header.setNome(nome);
        header.setValore(GdeUtils.maskSensitiveHeaderValue(nome, valore));
        return header;
    }

    private static String requestPayload(ContentCachingRequestWrapper request) {
        if (!isCaptureableContentType(request.getContentType())) {
            return BINARY_PLACEHOLDER;
        }
        byte[] content = request.getContentAsByteArray();
        return content.length == 0 ? null : Base64.getEncoder().encodeToString(content);
    }

    private static String responsePayload(GdeCapturingResponseWrapper response) {
        if (!isCaptureableContentType(response.getContentType())) {
            return BINARY_PLACEHOLDER;
        }
        byte[] content = response.getCapturedBytes();
        return content.length == 0 ? null : Base64.getEncoder().encodeToString(content);
    }

    /**
     * Considera catturabile (testuale/strutturato) solo un elenco esplicito di Content-Type;
     * tutto il resto riceve il placeholder fisso. Content-Type assente è considerato
     * catturabile ma produce comunque un payload vuoto/nullo.
     */
    private static boolean isCaptureableContentType(String contentType) {
        if (contentType == null) {
            return true;
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("application/json")
                || lower.startsWith("application/problem+json")
                || lower.startsWith("application/xml")
                || lower.startsWith("text/");
    }
}
