package it.govpay.pafornode.ricevuta;

/**
 * Stato di spedizione di un {@link Promemoria} (colonna legacy {@code promemoria.stato}).
 * Valori allineati esattamente al legacy {@code Promemoria.StatoSpedizione} —
 * {@code ANNULLATO}, non {@code ANNULLATA} (a differenza di
 * {@link StatoSpedizioneNotificaAppIo}: incoerenza del legacy, replicata fedelmente).
 */
public enum StatoSpedizionePromemoria {
    DA_SPEDIRE,
    SPEDITO,
    ANNULLATO,
    FALLITO
}
