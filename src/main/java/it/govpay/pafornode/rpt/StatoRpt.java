package it.govpay.pafornode.rpt;

import java.util.List;

/**
 * Stato di trasporto di una RPT verso il Nodo dei Pagamenti (codifica legacy
 * {@code it.govpay.model.Rpt.StatoRpt}), persistito come stringa grezza su
 * {@code Rpt.stato} (govpay-common-pendenze non lo tipizza, vedi Javadoc di classe di
 * {@code Rpt}).
 */
public enum StatoRpt {
	RPT_PARCHEGGIATA_NODO,
	RPT_ATTIVATA,
	RPT_ERRORE_INVIO_A_NODO,
	RPT_RICEVUTA_NODO,
	RPT_RIFIUTATA_NODO,
	RPT_ACCETTATA_NODO,
	RPT_RIFIUTATA_PSP,
	RPT_ACCETTATA_PSP,
	RPT_ERRORE_INVIO_A_PSP,
	RPT_INVIATA_A_PSP,
	RPT_DECORSI_TERMINI,
	RPT_ANNULLATA,
	RT_RICEVUTA_NODO,
	RT_RIFIUTATA_NODO,
	RT_ACCETTATA_NODO,
	RT_ACCETTATA_PA,
	RT_RIFIUTATA_PA,
	RT_ESITO_SCONOSCIUTO_PA,
	RT_ERRORE_INVIO_A_PA,
	INTERNO_NODO;

	/** Stati da annullare prima di attivare una nuova RPT sulla stessa coppia dominio/iuv. */
	public static final List<String> STATI_PENDENTI = List.of(RPT_PARCHEGGIATA_NODO.name(), RPT_ATTIVATA.name(),
			RPT_RICEVUTA_NODO.name(), RPT_ACCETTATA_NODO.name(), RPT_INVIATA_A_PSP.name(), RPT_ACCETTATA_PSP.name(),
			RPT_ANNULLATA.name(), RT_RICEVUTA_NODO.name(), RT_RIFIUTATA_NODO.name(), RT_ACCETTATA_NODO.name(),
			RT_RIFIUTATA_PA.name(), RT_ESITO_SCONOSCIUTO_PA.name(), RT_ERRORE_INVIO_A_PA.name(), INTERNO_NODO.name());
}
