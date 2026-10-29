package it.govpay.pafornode.ricevuta;

/**
 * Tipo di una {@link NotificaAppIo} (colonna legacy {@code notifiche_app_io.tipo_esito}).
 * Valori allineati esattamente al legacy {@code NotificaAppIo.TipoNotifica} — riuso diretto
 * della tabella.
 */
public enum TipoNotificaAppIo {
    AVVISO,
    SCADENZA,
    RICEVUTA,
    RICEVUTA_NO_RPT
}
