package it.govpay.pafornode.gde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import it.govpay.common.gde.GdeEventInfo;
import it.govpay.common.logging.TransactionContext;
import it.govpay.gde.client.beans.EsitoEvento;
import it.govpay.gde.client.beans.Header;
import jakarta.servlet.FilterChain;

class GdeEventFilterTest {

    private final PaForNodeGdeService paForNodeGdeService = mock(PaForNodeGdeService.class);
    private final GdeEventFilter filter = new GdeEventFilter(paForNodeGdeService, Clock.systemDefaultZone());

    @AfterEach
    void pulisciContestoTransazione() {
        TransactionContext.clear();
    }

    @Test
    void unhandledExceptionWithResponseStillOkIsClassifiedAsFailWith500() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // di default MockHttpServletResponse resta a 200: nessun exception handler
        // ha ancora avuto modo di impostare uno status di errore.
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getStatusCodeRisposta()).isEqualTo(500);
        assertThat(eventInfo.getEsito()).isEqualTo(EsitoEvento.FAIL);
        assertThat(eventInfo.getDescrizioneEsito()).isEqualTo("boom");
    }

    @Test
    void successfulRequestIsClassifiedAsOk() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getStatusCodeRisposta()).isEqualTo(200);
        assertThat(eventInfo.getEsito()).isEqualTo(EsitoEvento.OK);
        assertThat(eventInfo.getDescrizioneEsito()).isNull();
    }

    @Test
    void operationIdIsReadFromInterceptorAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) ->
                req.setAttribute(OperationIdEndpointInterceptor.OPERATION_ID_ATTRIBUTE, "paGetPayment");

        filter.doFilter(request, response, chain);

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getTipoEvento()).isEqualTo("paGetPayment");
    }

    @Test
    void operationIdIsUnknownWhenAttributeAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getTipoEvento()).isEqualTo("UNKNOWN");
    }

    @Test
    void principalIsReadFromInterceptorAttributeNotSecurityContextHolder() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // SecurityContextHolder e' vuoto in questo test: il principal arriva
        // solo dall'attributo che OperationIdEndpointInterceptor valorizzerebbe
        // a runtime (qui simulato direttamente, prima che la chain esegua).
        FilterChain chain = (req, res) ->
                req.setAttribute(OperationIdEndpointInterceptor.PRINCIPAL_ATTRIBUTE, "STAZIONE1");

        filter.doFilter(request, response, chain);

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getPrincipal()).isEqualTo("STAZIONE1");
    }

    @Test
    void principalIsNullWhenAttributeAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getPrincipal()).isNull();
    }

    /**
     * Il contratto paForNode restituisce un fault applicativo (incluso {@code
     * PAA_SYSTEM_ERROR}) con HTTP 200, mai un SOAP Fault — lo stato HTTP da solo
     * classificherebbe l'evento sempre come OK (bug segnalato in revisione). L'attributo di
     * {@link GdeFaultContext}, che {@code PaForNodeEndpoint} imposterebbe a runtime, ha
     * precedenza sullo stato HTTP (qui simulato direttamente).
     */
    @Test
    void faultApplicativoConHttp200EClassificatoComeKoNonOk() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        FilterChain chain = (req, res) -> {
            req.setAttribute(GdeFaultContext.FAULT_CODE_ATTRIBUTE, "PAA_PAGAMENTO_SCONOSCIUTO");
            req.setAttribute(GdeFaultContext.FAULT_DESCRIZIONE_ATTRIBUTE, "Pagamento in attesa risulta sconosciuto");
        };

        filter.doFilter(request, response, chain);

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getStatusCodeRisposta()).isEqualTo(200);
        assertThat(eventInfo.getEsito()).isEqualTo(EsitoEvento.KO);
        assertThat(eventInfo.getDescrizioneEsito())
                .isEqualTo("PAA_PAGAMENTO_SCONOSCIUTO: Pagamento in attesa risulta sconosciuto");
    }

    /** Come sopra, per {@code PAA_SYSTEM_ERROR} (percorso del catch generico, non di una {@code NdpException} esplicita). */
    @Test
    void paaSystemErrorConHttp200EClassificatoComeKoNonOk() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        FilterChain chain = (req, res) -> {
            req.setAttribute(GdeFaultContext.FAULT_CODE_ATTRIBUTE, "PAA_SYSTEM_ERROR");
            req.setAttribute(GdeFaultContext.FAULT_DESCRIZIONE_ATTRIBUTE, "NullPointerException");
        };

        filter.doFilter(request, response, chain);

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getStatusCodeRisposta()).isEqualTo(200);
        assertThat(eventInfo.getEsito()).isEqualTo(EsitoEvento.KO);
        assertThat(eventInfo.getDescrizioneEsito()).isEqualTo("PAA_SYSTEM_ERROR: NullPointerException");
    }

    @Test
    void handledClientErrorWithoutExceptionIsClassifiedAsKoNot500() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(404);

        filter.doFilter(request, response, chain);

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getStatusCodeRisposta()).isEqualTo(404);
        assertThat(eventInfo.getEsito()).isEqualTo(EsitoEvento.KO);
    }

    @Test
    void oscuraLHeaderAuthorizationConLeCredenzialiBasic() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        request.addHeader("Authorization", "Basic QTJBLVRFU1Q6dGVzdC1wYXNzd29yZA==");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        GdeEventInfo eventInfo = capturedEventInfo();
        Header authorization = eventInfo.getHeadersRichiesta().stream()
                .filter(h -> "Authorization".equalsIgnoreCase(h.getNome()))
                .findFirst()
                .orElseThrow();
        assertThat(authorization.getValore()).isEqualTo("***");
    }

    @Test
    void catturaIlPayloadTestualeTextXmlDelBindingSoap11() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        request.setContentType("text/xml; charset=UTF-8");
        request.setContent("<Envelope/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        // Legge il body solo se qualcuno lo consuma: simula cosa fa MessageDispatcherServlet.
        FilterChain chain = (req, res) -> req.getInputStream().readAllBytes();

        filter.doFilter(request, response, chain);

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getPayloadRichiesta())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString("<Envelope/>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void eventoPortaIlTransactionIdDelContestoCorrente() throws Exception {
        TransactionContext.setTransactionId("txn-di-prova");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        GdeEventInfo eventInfo = capturedEventInfo();
        assertThat(eventInfo.getTransactionId()).isEqualTo("txn-di-prova");
    }

    @Test
    void nonFiltraLeRichiesteAdActuator() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    private GdeEventInfo capturedEventInfo() {
        ArgumentCaptor<GdeEventInfo> captor = ArgumentCaptor.forClass(GdeEventInfo.class);
        verify(paForNodeGdeService).inviaEventoAsync(captor.capture());
        return captor.getValue();
    }
}
