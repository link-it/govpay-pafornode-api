package it.govpay.pafornode.rpt;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * {@code remittanceInformation} (causale) di un {@code transfer}, condivisa tra le
 * operazioni paForNode che attivano una RPT.
 *
 * <p>Non gestito il formato IUV ISO 11649 (prefisso {@code RF}, usato da schemi di
 * pagamento diversi da pagoPA): gli IUV generati da questo progetto sono sempre numerici,
 * quindi il ramo {@code /RFS/} non e' raggiungibile — solo {@code /RFB/}.</p>
 */
public final class RemittanceInformationBuilder {

	/** Limite {@code stText140} dello schema pagoPA. */
	private static final int LUNGHEZZA_MASSIMA = 140;

	private static final DecimalFormat IMPORTO_FORMATTER = new DecimalFormat("0.00",
			new DecimalFormatSymbols(Locale.ENGLISH));

	private RemittanceInformationBuilder() {
	}

	public static String costruisci(String iuv, BigDecimal importoSingoloVersamento, String descrizione,
			String descrizioneCausaleRpt) {
		StringBuilder causale = new StringBuilder("/RFB/").append(iuv).append('/')
				.append(IMPORTO_FORMATTER.format(importoSingoloVersamento));
		if (descrizioneCausaleRpt != null && !descrizioneCausaleRpt.isBlank()) {
			causale.append("/TXT/").append(descrizioneCausaleRpt);
		} else if (descrizione != null && !descrizione.isBlank()) {
			causale.append("/TXT/").append(descrizione);
		}
		return causale.length() > LUNGHEZZA_MASSIMA ? causale.substring(0, LUNGHEZZA_MASSIMA) : causale.toString();
	}
}
