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

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;

import it.govpay.common.client.model.Connettore;
import it.govpay.common.client.service.ConnettoreService;

/**
 * Client della sola operazione SEND NotificationPriceV23. Usa i RestTemplate del
 * connettore per autenticazione, timeout, TLS, correlation ID, GDE e observation.
 * Non applica retry, conversioni in euro o aggiornamenti della pendenza.
 */
@Service
public class SendClient {

    public static final String NOTIFICATION_PRICE_PATH = "/delivery/v2.3/price/{paTaxId}/{noticeCode}";

    private final ConnettoreService connettoreService;

    public SendClient(ConnettoreService connettoreService) {
        this.connettoreService = connettoreService;
    }

    /**
     * Recupera totalPrice (comprensivo di paFee e IVA), espresso in eurocent.
     *
     * @param codConnettoreSend codice del connettore configurato sul dominio
     * @param paTaxId codice fiscale dell'ente creditore
     * @param noticeCode numero avviso completo, non lo IUV
     * @return costo totale in centesimi, anche zero
     * @throws RestClientException per errori HTTP/trasporto o risposta senza un
     *         totalPrice intero non negativo; gli errori HTTP conservano status e body
     */
    public long recuperaImportoNotifica(String codConnettoreSend, String paTaxId, String noticeCode) {
        Assert.hasText(codConnettoreSend, "codConnettoreSend obbligatorio");
        Assert.hasText(paTaxId, "paTaxId obbligatorio");
        Assert.hasText(noticeCode, "noticeCode obbligatorio");

        Connettore connettore = connettoreService.getConnettore(codConnettoreSend);
        Assert.hasText(connettore.getUrl(), "URL del connettore SEND obbligatorio");
        String baseUrl = connettore.getUrl().replaceAll("/+$", "");
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path(NOTIFICATION_PRICE_PATH).encode().buildAndExpand(paTaxId, noticeCode).toUri();
        RequestEntity<Void> richiesta = RequestEntity.get(uri).accept(MediaType.APPLICATION_JSON).build();
        ResponseEntity<JsonNode> risposta = connettoreService.getRestTemplate(codConnettoreSend)
                .exchange(richiesta, JsonNode.class);

        JsonNode body = risposta.getBody();
        JsonNode prezzo = body == null ? null : body.get("totalPrice");
        // Non usare asLong(): coercizioni, null o campi mancanti non devono diventare
        // una spesa zero. Gli altri campi della risposta non servono a questa operazione.
        if (risposta.getStatusCode() != HttpStatus.OK || prezzo == null
                || !prezzo.isIntegralNumber() || !prezzo.canConvertToLong() || prezzo.longValue() < 0) {
            throw new RestClientException("Risposta SEND non valida: atteso totalPrice intero non negativo in eurocent");
        }
        return prezzo.longValue();
    }
}
