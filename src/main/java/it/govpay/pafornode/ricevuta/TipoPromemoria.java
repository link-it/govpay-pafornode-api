package it.govpay.pafornode.ricevuta;

/**
 * Tipo di un {@link Promemoria} (colonna legacy {@code promemoria.tipo}). Valori allineati
 * esattamente al legacy {@code Promemoria.TipoPromemoria} — riuso diretto della tabella.
 */
public enum TipoPromemoria {
    AVVISO,
    RICEVUTA,
    SCADENZA,
    RICEVUTA_NO_RPT
}
