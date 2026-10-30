package it.govpay.pafornode.rpt;

import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.entity.PosizioneDebitoria;

/** Risoluzione del nome dell'unita' operativa per {@code officeName}, condivisa tra le operazioni paForNode. */
public final class UnitaOperativaResolver {

	private static final String COD_UNITA_OPERATIVA_EC = "EC";

	private UnitaOperativaResolver() {
	}

	/** {@code null} se l'unita' operativa non e' configurata o e' l'ente creditore stesso (EC). */
	public static String risolviNome(UnitaOperativaRepository unitaOperativaRepository, PosizioneDebitoria posizione) {
		if (posizione.getIdUnitaOperativa() == null) {
			return null;
		}
		return unitaOperativaRepository.findById(posizione.getIdUnitaOperativa())
				.filter(uo -> !COD_UNITA_OPERATIVA_EC.equals(uo.getCodUo())).map(UnitaOperativaEntity::getUoDenominazione)
				.orElse(null);
	}
}
