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
}
