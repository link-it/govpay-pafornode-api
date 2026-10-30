package it.govpay.pafornode.rpt;

import it.govpay.common.entity.TipoContabilita;
import it.govpay.common.entity.TributoEntity;
import it.govpay.pendenze.entity.VocePendenza;

/**
 * Risoluzione di {@code tipoContabilita}/{@code codiceContabilita} di una {@link VocePendenza},
 * condivisa tra le operazioni paForNode che attivano una RPT: ciascuno dei due campi, se
 * assente sulla voce, e' recuperato dal tributo — indipendentemente dall'altro (stesso
 * meccanismo del legacy, {@code SingoloVersamento#getTipoContabilita(BDConfigWrapper)}/
 * {@code #getCodContabilita(BDConfigWrapper)}: due fallback separati, non "se uno manca uso
 * il tributo per entrambi").
 */
public final class ContabilitaResolver {

	private ContabilitaResolver() {
	}

	public record TipoCodice(TipoContabilita tipo, String codice) {
	}

	public static TipoCodice risolvi(VocePendenza voce, TributoEntity tributo) {
		TipoContabilita tipo = voce.getTipoContabilita() != null ? voce.getTipoContabilita()
				: (tributo != null ? tributo.getTipoContabilita() : null);
		String codice = voce.getCodiceContabilita() != null ? voce.getCodiceContabilita()
				: (tributo != null ? tributo.getCodiceContabilita() : null);
		return new TipoCodice(tipo, codice);
	}

	/**
	 * Come {@link #risolvi}, ma restituisce direttamente la stringa {@code
	 * transferCategory} ({@code tipo/codice}) — richiede ENTRAMBI i componenti: con un solo
	 * componente assente il risultato sarebbe una stringa incoerente (es. {@code "9/null"})
	 * invece di un fallimento esplicito.
	 *
	 * @throws IllegalStateException se {@code tipoContabilita}/{@code codiceContabilita} non
	 *                                sono risolvibili entrambi (voce e tributo, se presente)
	 */
	public static String transferCategory(VocePendenza voce, TributoEntity tributo) {
		TipoCodice contabilita = risolvi(voce, tributo);
		if (contabilita.tipo() == null || contabilita.codice() == null) {
			throw new IllegalStateException("la voce [" + voce.getIdVocePendenza() + "] non ha una classificazione "
					+ "contabile risolvibile (tipoContabilita/codiceContabilita assenti su voce e tributo): "
					+ "transferCategory non costruibile.");
		}
		return contabilita.tipo().getCodifica() + "/" + contabilita.codice();
	}
}
