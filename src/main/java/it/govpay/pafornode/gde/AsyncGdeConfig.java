package it.govpay.pafornode.gde;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import it.govpay.common.logging.MdcTaskDecorator;

/**
 * Bean {@code gdeExecutor} usato da {@link PaForNodeGdeService} per l'invio asincrono degli
 * eventi GDE. A differenza dell'equivalente in {@code govpay-pendenze-api}, qui NON è anche
 * alias di {@code asyncHttpExecutor}: quel nome è già fornito da
 * {@code it.govpay.common.client.config.AsyncClientConfiguration} (importata in
 * {@code HttpClientConfig} per SEND/Verifica) — un pool separato e più piccolo, dedicato solo
 * al GDE, evita sia la collisione di nomi sia di far competere l'invio degli eventi GDE con le
 * chiamate ai connettori applicativi sullo stesso pool.
 * <p>
 * Se {@code app.gde.async=true} (default): {@link ThreadPoolTaskExecutor} con pool dedicato
 * {@code gde-*}. Se {@code app.gde.async=false} (impostato nel profilo test): {@link
 * SyncTaskExecutor}, cosi' i test che verificano l'invio dell'evento lo trovano senza dover
 * attendere il thread async.
 * <p>
 * {@link MdcTaskDecorator} è indispensabile sul ramo asincrono: senza, transaction id e
 * correlation id (in {@code TransactionContext}, un ThreadLocal) non attraversano il cambio di
 * thread verso il pool — {@code AbstractGdeService#inviaEvento} eseguito sul thread {@code
 * gde-*} troverebbe un contesto vuoto, perdendo sia la correlazione nei log di quell'invio sia
 * la propagazione del correlation id alla chiamata HTTP verso il GDE. Il ramo sincrono non ne
 * ha bisogno: gira sul thread chiamante, dove il contesto è già presente.
 */
@Configuration
public class AsyncGdeConfig {

    @Bean(name = "gdeExecutor")
    public TaskExecutor gdeExecutor(@Value("${app.gde.async:true}") boolean async,
                                     @Value("${app.gde.pool.core-size:2}") int coreSize,
                                     @Value("${app.gde.pool.max-size:4}") int maxSize,
                                     @Value("${app.gde.pool.queue-capacity:100}") int queueCapacity) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(coreSize);
        exec.setMaxPoolSize(maxSize);
        exec.setQueueCapacity(queueCapacity);
        exec.setThreadNamePrefix("gde-");
        // l'invio GDE non deve mai bloccare la response: log e drop
        exec.setRejectedExecutionHandler((r, e) -> org.slf4j.LoggerFactory.getLogger(AsyncGdeConfig.class)
                .warn("Evento GDE rifiutato dall'executor (pool saturo): evento droppato."));
        exec.setTaskDecorator(new MdcTaskDecorator());
        exec.initialize();
        return exec;
    }
}
