package it.govpay.pafornode.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import it.govpay.common.client.config.AsyncClientConfiguration;
import it.govpay.common.client.factory.RestTemplateFactory;
import it.govpay.common.client.oauth2.Oauth2ClientCredentialsManager;
import it.govpay.common.client.service.ConnettoreService;
import it.govpay.common.configurazione.service.ConfigurazioneService;

/**
 * Infrastruttura HTTP comune per i client del servizio, incluso SEND. Import mirati:
 * GovPayClientAutoConfiguration registrerebbe anche repository ed entity gia' gestiti da
 * Application, allargando lo scan alle entity batch.
 * <p>
 * {@code ConfigurazioneService} è qui (non in un file a parte) perché dipende da {@code
 * ConnettoreService}, già importato in questa stessa classe — risolve il connettore GDE per
 * {@code it.govpay.pafornode.gde.PaForNodeGdeService} e, se in futuro abilitati, i livelli di
 * log dinamici (prerequisito dichiarato nel Javadoc di {@code Application}).
 */
@Configuration(proxyBeanMethods = false)
@Import({ AsyncClientConfiguration.class, RestTemplateFactory.class,
        Oauth2ClientCredentialsManager.class, ConnettoreService.class, ConfigurazioneService.class })
public class HttpClientConfig {
}
