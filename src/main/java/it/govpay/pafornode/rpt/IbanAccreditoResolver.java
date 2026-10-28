package it.govpay.pafornode.rpt;

import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.pendenze.entity.VocePendenza;

/**
 * Risoluzione dell'IBAN di accredito/appoggio di una {@link VocePendenza}, condivisa tra le
 * operazioni paForNode che attivano una RPT: se la voce ha un tributo, l'IBAN e' quello del
 * tributo; altrimenti e' quello indicato direttamente sulla voce.
 *
 * <p>Il tributo e' un parametro (non risolto qui): lo stesso tributo serve anche a
 * {@link ContabilitaResolver}, il chiamante lo risolve una sola volta per voce.</p>
 */
public final class IbanAccreditoResolver {

	private IbanAccreditoResolver() {
	}

	public record Coppia(IbanAccreditoEntity ibanAccredito, IbanAccreditoEntity ibanAppoggio) {
	}

	public static Coppia risolvi(IbanAccreditoRepository ibanAccreditoRepository, VocePendenza voce,
			TributoEntity tributo) {
		if (tributo != null) {
			return new Coppia(tributo.getIbanAccredito(), tributo.getIbanAppoggio());
		}
		IbanAccreditoEntity ibanAccredito = voce.getIdIbanAccredito() != null
				? ibanAccreditoRepository.findById(voce.getIdIbanAccredito()).orElse(null) : null;
		IbanAccreditoEntity ibanAppoggio = voce.getIdIbanAppoggio() != null
				? ibanAccreditoRepository.findById(voce.getIdIbanAppoggio()).orElse(null) : null;
		return new Coppia(ibanAccredito, ibanAppoggio);
	}

	/**
	 * Solo il primo transfer e' vincolato a essere postale se richiesto (l'IBAN di appoggio
	 * viene preferito se e' lui, non l'accredito, a essere postale); gli altri transfer non
	 * hanno questo vincolo.
	 */
	public static IbanAccreditoEntity scegli(Coppia coppia, boolean primoTransfer, boolean richiestoIbanPostale) {
		IbanAccreditoEntity ibanAccredito = coppia.ibanAccredito();
		IbanAccreditoEntity ibanAppoggio = coppia.ibanAppoggio();
		if (!primoTransfer) {
			return ibanAccredito != null ? ibanAccredito : ibanAppoggio;
		}
		if (ibanAccredito == null) {
			return ibanAppoggio;
		}
		if (richiestoIbanPostale && !Boolean.TRUE.equals(ibanAccredito.getPostale()) && ibanAppoggio != null
				&& Boolean.TRUE.equals(ibanAppoggio.getPostale())) {
			return ibanAppoggio;
		}
		return ibanAccredito;
	}
}
