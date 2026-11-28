package it.govpay.pafornode.rpt;

import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.entity.PosizioneDebitoria;

/**
 * Risoluzione dell'unita' operativa di una posizione debitoria, condivisa tra le operazioni paForNode:
 * l'id da salvare in creazione e il nome per {@code officeName}.
 */
public final class UnitaOperativaResolver {

	private static final String COD_UNITA_OPERATIVA_EC = "EC";

	private UnitaOperativaResolver() {
	}

	/**
	 * Id dell'unita' operativa da salvare su una nuova posizione debitoria: il codice omesso vale
	 * {@value #COD_UNITA_OPERATIVA_EC}.
	 *
	 * @throws IllegalStateException se l'unita' operativa non esiste per il dominio o e' disabilitata
	 */
	public static Long risolviIdPerCreazione(UnitaOperativaRepository unitaOperativaRepository, Long idDominio,
			String codUnitaOperativa) {
		String codice = codUnitaOperativa != null ? codUnitaOperativa : COD_UNITA_OPERATIVA_EC;
		UnitaOperativaEntity unitaOperativa = unitaOperativaRepository.findByCodUoAndDominioId(codice, idDominio)
				.orElseThrow(() -> new IllegalStateException(
						"nessuna unita' operativa [" + codice + "] per il dominio [id:" + idDominio + "]"));
		if (Boolean.FALSE.equals(unitaOperativa.getAbilitato())) {
			throw new IllegalStateException("l'unita' operativa [" + codice + "] per il dominio [id:" + idDominio
					+ "] non e' abilitata");
		}
		return unitaOperativa.getId();
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
