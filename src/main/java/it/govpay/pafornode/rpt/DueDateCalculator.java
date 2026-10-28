package it.govpay.pafornode.rpt;

import java.time.LocalDate;

import it.govpay.pendenze.entity.Pendenza;

/**
 * {@code dueDate} della RPT, condiviso tra le operazioni paForNode che la attivano:
 * {@code dataValidita} della pendenza, altrimenti {@code dataScadenzaAvviso}, altrimenti
 * {@code numeroGiorniValiditaPendenza} giorni dalla creazione, altrimenti una scadenza
 * convenzionale lontana nel tempo (nessuna delle precedenti configurata).
 */
public final class DueDateCalculator {

	private static final LocalDate NESSUNA_SCADENZA_NOTA = LocalDate.of(2999, 12, 31);

	private DueDateCalculator() {
	}

	public static LocalDate calcola(Pendenza pendenza, Integer numeroGiorniValiditaPendenza) {
		if (pendenza.getDataValidita() != null) {
			return pendenza.getDataValidita().toLocalDate();
		}
		if (pendenza.getDataScadenzaAvviso() != null) {
			return pendenza.getDataScadenzaAvviso().toLocalDate();
		}
		if (numeroGiorniValiditaPendenza != null && pendenza.getDataCreazione() != null) {
			return pendenza.getDataCreazione().toLocalDate().plusDays(numeroGiorniValiditaPendenza);
		}
		return NESSUNA_SCADENZA_NOTA;
	}
}
