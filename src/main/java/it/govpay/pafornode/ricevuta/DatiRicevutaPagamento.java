package it.govpay.pafornode.ricevuta;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Dati di una ricevuta di pagamento (RT) pagoPA gia' estratti dal formato del contratto
 * ({@code ctReceipt}/{@code ctReceiptV2}) dal chiamante ({@code PaSendRTService}) — valori
 * grezzi, non i tipi JAXB generati: {@link AcquisizioneRicevutaService} non dipende dal
 * contratto paForNode (a differenza di
 * {@link it.govpay.pendenze.ricevuta.pagopa.RicostruttoreRichiestaPagamento} di
 * govpay-common-pendenze, che li usa per un motivo diverso — ricostruire un payload mai
 * salvato, non acquisirne uno appena arrivato).
 *
 * @param receiptId           identificativo univoco di riscossione (IUR) — colonna
 *                            fisica {@code ccp} di {@code Rpt} (vedi Javadoc di classe)
 * @param eseguito             {@code true} se l'esito del pagamento e' OK, {@code false}
 *                            se KO ({@code ctReceipt.getOutcome()}) — binario per il
 *                            contratto paForNode, a differenza del vecchio SANP_230
 *                            (vedi Javadoc di classe di {@link AcquisizioneRicevutaService})
 * @param dataPagamento       istante di esecuzione del pagamento da parte del debitore
 * @param importoTotalePagato importo complessivo pagato (somma dei trasferimenti)
 * @param pspFiscalCode       identificativo fiscale del PSP attestante
 * @param pspCompanyName      denominazione del PSP attestante
 * @param idPSP               identificativo del PSP
 * @param idChannel           identificativo del canale di pagamento usato
 * @param tipoVersamento      metodo di pagamento dichiarato dalla ricevuta
 * @param xmlRt               corpo XML grezzo della ricevuta, per {@code Rpt.xmlRt}
 * @param trasferimenti       un elemento per ogni trasferimento della ricevuta, nello
 *                            stesso ordine delle voci della pendenza (stessa assunzione
 *                            posizionale del legacy, vedi Javadoc di classe)
 */
public record DatiRicevutaPagamento(String receiptId, boolean eseguito, OffsetDateTime dataPagamento,
        double importoTotalePagato, String pspFiscalCode, String pspCompanyName, String idPSP, String idChannel,
        String tipoVersamento, byte[] xmlRt, List<DatiTrasferimentoRicevuto> trasferimenti) {

    /**
     * Un trasferimento della ricevuta.
     *
     * @param idTransfer     identificativo del trasferimento ({@code CtTransferPA(V2)
     *                       Receipt.getIdTransfer()}) — diventa {@code Pagamento.indiceDati}
     * @param transferAmount importo pagato per questo trasferimento; {@code 0} significa
     *                       "nessun pagamento completato per questa voce" (stesso
     *                       significato del legacy: la voce viene saltata)
     */
    public record DatiTrasferimentoRicevuto(int idTransfer, double transferAmount) {
    }
}
