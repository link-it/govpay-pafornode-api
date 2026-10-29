package it.govpay.pafornode.ricevuta;

/**
 * Stato di spedizione di una {@link NotificaAppIo} (colonna legacy
 * {@code notifiche_app_io.stato}). Valori allineati esattamente al legacy
 * {@code NotificaAppIo.StatoSpedizione} — {@code ANNULLATA}, non {@code ANNULLATO} (a
 * differenza di {@link StatoSpedizionePromemoria}: incoerenza del legacy, replicata
 * fedelmente).
 */
public enum StatoSpedizioneNotificaAppIo {
    DA_SPEDIRE,
    SPEDITO,
    ANNULLATA
}
