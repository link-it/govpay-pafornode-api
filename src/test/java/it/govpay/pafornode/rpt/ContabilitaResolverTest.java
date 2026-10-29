package it.govpay.pafornode.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import it.govpay.common.entity.TipoContabilita;
import it.govpay.common.entity.TributoEntity;
import it.govpay.pendenze.entity.VocePendenza;

class ContabilitaResolverTest {

	private VocePendenza voce() {
		VocePendenza voce = new VocePendenza();
		voce.setIdVocePendenza("voce-1");
		return voce;
	}

	@Test
	void componeTransferCategoryDaiCampiDellaVoce() {
		VocePendenza voce = voce();
		voce.setTipoContabilita(TipoContabilita.ALTRO);
		voce.setCodiceContabilita("3321");

		assertThat(ContabilitaResolver.transferCategory(voce, null)).isEqualTo("9/3321");
	}

	@Test
	void componeTransferCategoryDalTributoSeLaVoceNonLiSpecifica() {
		VocePendenza voce = voce();
		TributoEntity tributo = new TributoEntity();
		tributo.setTipoContabilita(TipoContabilita.CAPITOLO);
		tributo.setCodiceContabilita("001");

		assertThat(ContabilitaResolver.transferCategory(voce, tributo)).isEqualTo("0/001");
	}

	@Test
	void rifiutaSeNessunaFonteRisolveTipoECodice() {
		VocePendenza voce = voce();

		assertThatThrownBy(() -> ContabilitaResolver.transferCategory(voce, null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("voce-1");
	}

	/**
	 * Entrambi i componenti vanno verificati: con un solo controllo (es. solo {@code tipo})
	 * il risultato sarebbe una stringa incoerente come {@code "9/null"}, non un fallimento.
	 */
	@Test
	void rifiutaSeMancaSoloIlCodiceAncheConTipoPresente() {
		VocePendenza voce = voce();
		voce.setTipoContabilita(TipoContabilita.ALTRO);

		assertThatThrownBy(() -> ContabilitaResolver.transferCategory(voce, null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("voce-1");
	}

	@Test
	void rifiutaSeMancaSoloIlTipoAncheConCodicePresente() {
		VocePendenza voce = voce();
		voce.setCodiceContabilita("3321");

		assertThatThrownBy(() -> ContabilitaResolver.transferCategory(voce, null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("voce-1");
	}
}
