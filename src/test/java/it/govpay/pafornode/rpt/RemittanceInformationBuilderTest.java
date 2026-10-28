package it.govpay.pafornode.rpt;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class RemittanceInformationBuilderTest {

	@Test
	void componeCausaleConPrefissoIuvEImporto() {
		String causale = RemittanceInformationBuilder.costruisci("01234567890123456789", BigDecimal.valueOf(16.00),
				"descrizione", null);

		assertThat(causale).isEqualTo("/RFB/01234567890123456789/16.00/TXT/descrizione");
	}

	@Test
	void troncaA140CaratteriLoStext140DelloSchemaPagopa() {
		String descrizioneLunga = "X".repeat(200);

		String causale = RemittanceInformationBuilder.costruisci("01234567890123456789", BigDecimal.valueOf(16.00),
				descrizioneLunga, null);

		assertThat(causale).hasSize(140);
	}
}
