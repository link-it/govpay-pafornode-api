package it.govpay.pafornode.gde;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import it.govpay.common.logging.TransactionContext;

/**
 * Verifica che il pool asincrono propaghi transaction id e correlation id al thread di
 * lavoro, non un dettaglio implementativo (es. "e' impostato un TaskDecorator"): senza
 * {@code MdcTaskDecorator} il {@code ThreadLocal} di {@code TransactionContext} resterebbe
 * vuoto sul thread {@code gde-*}, interrompendo sia la correlazione nei log dell'invio sia
 * la propagazione del correlation id alla chiamata HTTP verso il GDE.
 */
class AsyncGdeConfigTest {

    private final AsyncGdeConfig config = new AsyncGdeConfig();

    @AfterEach
    void pulisciContesto() {
        TransactionContext.clear();
    }

    @Test
    void ilPoolAsincronoPropagaTransactionIdECorrelationIdAlThreadDiLavoro() throws Exception {
        TaskExecutor executor = config.gdeExecutor(true, 2, 4, 100);
        TransactionContext.setTransactionId("txn-test");
        TransactionContext.setCorrelationId("corr-test");

        CompletableFuture<String[]> risultato = new CompletableFuture<>();
        executor.execute(() -> risultato.complete(
                new String[] { TransactionContext.getTransactionId(), TransactionContext.getCorrelationId() }));

        String[] valoriNelThreadDelPool = risultato.get(5, TimeUnit.SECONDS);
        assertThat(valoriNelThreadDelPool).containsExactly("txn-test", "corr-test");
    }

    @Test
    void ilPoolSincronoNonHaBisognoDiPropagazioneEsegueSulThreadChiamante() {
        TaskExecutor executor = config.gdeExecutor(false, 2, 4, 100);
        TransactionContext.setTransactionId("txn-test");

        String[] transactionIdVisto = new String[1];
        String threadChiamante = Thread.currentThread().getName();
        String[] threadVisto = new String[1];
        executor.execute(() -> {
            transactionIdVisto[0] = TransactionContext.getTransactionId();
            threadVisto[0] = Thread.currentThread().getName();
        });

        assertThat(transactionIdVisto[0]).isEqualTo("txn-test");
        assertThat(threadVisto[0]).isEqualTo(threadChiamante);
    }
}
