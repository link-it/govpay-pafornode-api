package it.govpay.pafornode.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import it.govpay.common.client.config.AsyncClientConfiguration;
import it.govpay.common.client.factory.RestTemplateFactory;
import it.govpay.common.client.oauth2.Oauth2ClientCredentialsManager;
import it.govpay.common.client.service.ConnettoreService;

/**
 * Infrastruttura HTTP comune per i client del servizio, incluso SEND.
 * Import mirati: GovPayClientAutoConfiguration registrerebbe anche repository ed
 * entity gia' gestiti da Application, allargando lo scan alle entity batch.
 */
@Configuration(proxyBeanMethods = false)
@Import({ AsyncClientConfiguration.class, RestTemplateFactory.class,
        Oauth2ClientCredentialsManager.class, ConnettoreService.class })
public class HttpClientConfig {
}
