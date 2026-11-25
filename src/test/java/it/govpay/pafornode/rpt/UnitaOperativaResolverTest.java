package it.govpay.pafornode.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.entity.PosizioneDebitoria;

/** Nome dell'unita' operativa riportato in {@code officeName}. */
class UnitaOperativaResolverTest {

	private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);

	private PosizioneDebitoria posizioneConUnitaOperativa(String codUo) {
		UnitaOperativaEntity unitaOperativa = new UnitaOperativaEntity();
		unitaOperativa.setCodUo(codUo);
		unitaOperativa.setUoDenominazione("Ufficio " + codUo);
		when(unitaOperativaRepository.findById(5L)).thenReturn(Optional.of(unitaOperativa));
		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setIdUnitaOperativa(5L);
		return posizione;
	}

	@Test
	void riportaIlNomeDiUnaUnitaOperativaSpecifica() {
		assertThat(UnitaOperativaResolver.risolviNome(unitaOperativaRepository, posizioneConUnitaOperativa("UO1")))
				.isEqualTo("Ufficio UO1");
	}

	@Test
	void nonRiportaLaUnitaOperativaEc() {
		assertThat(UnitaOperativaResolver.risolviNome(unitaOperativaRepository, posizioneConUnitaOperativa("EC")))
				.isNull();
	}

	@Test
	void senzaUnitaOperativaNonLeggeLAnagrafica() {
		assertThat(UnitaOperativaResolver.risolviNome(unitaOperativaRepository, new PosizioneDebitoria())).isNull();
		verifyNoInteractions(unitaOperativaRepository);
	}
}
