package it.govpay.pafornode.gde;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.MethodEndpoint;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;

import java.util.List;

class OperationIdEndpointInterceptorTest {

    private final OperationIdEndpointInterceptor interceptor = new OperationIdEndpointInterceptor();

    @AfterEach
    void pulisciContesti() {
        TransportContextHolder.setTransportContext(null);
        SecurityContextHolder.clearContext();
    }

    @Test
    void impostaIlNomeDelMetodoDellEndpointComeOperationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        impostaTransportContext(request);
        MethodEndpoint endpoint = new MethodEndpoint(new EndpointDiProva(), "paGetPayment");

        boolean continua = interceptor.handleRequest(mockMessageContext(), endpoint);

        assertThat(continua).isTrue();
        assertThat(request.getAttribute(OperationIdEndpointInterceptor.OPERATION_ID_ATTRIBUTE))
                .isEqualTo("paGetPayment");
    }

    @Test
    void impostaIlPrincipalAutenticato() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        impostaTransportContext(request);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("STAZIONE1", null, List.of()));

        interceptor.handleRequest(mockMessageContext(), new MethodEndpoint(new EndpointDiProva(), "paGetPayment"));

        assertThat(request.getAttribute(OperationIdEndpointInterceptor.PRINCIPAL_ATTRIBUTE))
                .isEqualTo("STAZIONE1");
    }

    @Test
    void nonImpostaIlPrincipalQuandoAnonimo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        impostaTransportContext(request);
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser", List.of(() -> "ROLE_ANONYMOUS")));

        interceptor.handleRequest(mockMessageContext(), new MethodEndpoint(new EndpointDiProva(), "paGetPayment"));

        assertThat(request.getAttribute(OperationIdEndpointInterceptor.PRINCIPAL_ATTRIBUTE)).isNull();
    }

    @Test
    void nonFalliscePerUnEndpointNonDiTipoMethodEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        impostaTransportContext(request);

        boolean continua = interceptor.handleRequest(mockMessageContext(), new Object());

        assertThat(continua).isTrue();
        assertThat(request.getAttribute(OperationIdEndpointInterceptor.OPERATION_ID_ATTRIBUTE)).isNull();
    }

    @Test
    void nonFalliscePerAssenzaDiTransportContext() throws Exception {
        boolean continua = interceptor.handleRequest(mockMessageContext(),
                new MethodEndpoint(new EndpointDiProva(), "paGetPayment"));

        assertThat(continua).isTrue();
    }

    private static void impostaTransportContext(MockHttpServletRequest request) {
        HttpServletConnection connection = new HttpServletConnection(request, new MockHttpServletResponse()) { };
        TransportContext transportContext = () -> connection;
        TransportContextHolder.setTransportContext(transportContext);
    }

    private static MessageContext mockMessageContext() {
        return org.mockito.Mockito.mock(MessageContext.class);
    }

    @SuppressWarnings("unused")
    private static final class EndpointDiProva {
        public void paGetPayment() {
            // metodo di prova, mai invocato: serve solo come Method risolvibile via reflection.
        }
    }
}
