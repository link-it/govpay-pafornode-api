/*
 * GovPay - Porta di Accesso al Nodo dei Pagamenti SPC
 * http://www.gov4j.it/govpay
 *
 * Copyright (c) 2014-2026 Link.it srl (http://www.link.it).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3, as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.govpay.pafornode.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.ObjectMapper;

import it.govpay.common.client.factory.RestTemplateFactory;
import it.govpay.common.client.model.Connettore;
import it.govpay.common.client.oauth2.Oauth2ClientCredentialsManager;
import it.govpay.common.client.service.ConnettoreService;
import it.govpay.common.entity.TipoAutenticazione;

class SendClientTest {

    private static final String CF = "77777777777";
    private static final String NAV = "302000100000019421";
    private static final String URL = "https://send.test/proxy/delivery/v2.3/price/" + CF + "/" + NAV;
    private SendClient client;
    private MockRestServiceServer server;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setup() {
        Connettore connettore = Connettore.builder().idConnettore("SEND_TEST")
                .url("https://send.test/proxy/").tipoAutenticazione(TipoAutenticazione.API_KEY)
                .apiId("x-api-key").apiKey("test-key").customHeaders(Map.of("X-Test", "custom")).build();
        RestTemplateFactory factory = new RestTemplateFactory(mock(Oauth2ClientCredentialsManager.class),
                new ObjectMapper());
        meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        factory.setObservationRegistry(provider);
        RestTemplate template = factory.createRestTemplate(connettore);
        server = MockRestServiceServer.bindTo(template).build();
        ConnettoreService connettori = mock(ConnettoreService.class);
        when(connettori.getConnettore("SEND_TEST")).thenReturn(connettore);
        when(connettori.getRestTemplate("SEND_TEST")).thenReturn(template);
        client = new SendClient(connettori);
    }

    @Test
    void leggeTotaleInCentesimiConAutenticazioneEObservationDelConnettore() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", "application/json"))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("X-Test", "custom"))
                .andRespond(withSuccess("{\"totalPrice\":256,\"partialPrice\":100,\"iun\":\"test\",\"futureField\":true}", MediaType.APPLICATION_JSON));
        assertThat(client.recuperaImportoNotifica("SEND_TEST", CF, NAV)).isEqualTo(256L);
        server.verify();
        assertThat(meterRegistry.find("http.client.requests").tag("connettore", "SEND_TEST").timer())
                .isNotNull();
    }

    @Test
    void ammetteCostoZero() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"totalPrice\":0}", MediaType.APPLICATION_JSON));
        assertThat(client.recuperaImportoNotifica("SEND_TEST", CF, NAV)).isZero();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"totalPrice\":null}", "{\"totalPrice\":-1}",
            "{\"totalPrice\":1.5}", "{\"totalPrice\":\"123\"}", "{\"totalPrice\":9223372036854775808}", "", "not-json"})
    void rifiutaRisposteInutilizzabiliSenzaInventareUnCosto(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.recuperaImportoNotifica("SEND_TEST", CF, NAV))
                .isInstanceOf(RestClientException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 429, 500})
    void propagaStatusEBodyDegliErroriHttp(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"detail\":\"errore SEND\"}"));
        assertThatThrownBy(() -> client.recuperaImportoNotifica("SEND_TEST", CF, NAV))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(status);
                    assertThat(e.getResponseBodyAsString()).contains("errore SEND");
                });
        server.verify();
    }

    @Test
    void propagaErroreDiTrasporto() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("timeout")));
        assertThatThrownBy(() -> client.recuperaImportoNotifica("SEND_TEST", CF, NAV))
                .isInstanceOf(RestClientException.class).hasCauseInstanceOf(IOException.class);
        server.verify();
    }

    @Test
    void codificaIParametriComeSegmentiDiPath() {
        server.expect(requestTo("https://send.test/proxy/delivery/v2.3/price/ente%2Ftest/avviso%3Ftest"))
                .andRespond(withSuccess("{\"totalPrice\":1}", MediaType.APPLICATION_JSON));
        assertThat(client.recuperaImportoNotifica("SEND_TEST", "ente/test", "avviso?test")).isEqualTo(1);
        server.verify();
    }
}
