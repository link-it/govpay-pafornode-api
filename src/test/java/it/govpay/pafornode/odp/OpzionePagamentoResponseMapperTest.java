package it.govpay.pafornode.odp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.govpay.pafornode.odp.api.model.EnumInstallment;
import it.govpay.pafornode.odp.api.model.EnumPo;
import it.govpay.pafornode.odp.api.model.PaymentOption;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;

/**
 * Verifica diretta della tabella di mappatura stato &rarr; enum pagoPA (decisa con l'utente,
 * vedi Javadoc di {@link OpzionePagamentoResponseMapper}): isolata dal service, nessun mock.
 */
class OpzionePagamentoResponseMapperTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneId.of("Europe/Rome"));
	private static final OffsetDateTime IERI = OffsetDateTime.now(CLOCK).minusDays(1);
	private static final OffsetDateTime DOMANI = OffsetDateTime.now(CLOCK).plusDays(1);

	private PosizioneDebitoria posizioneConUnaOpzione(StatoOpzionePagamento statoOpzione, LocalDate dataScadenzaOpzione,
			Pendenza... pendenze) {
		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setDescrizione("Posizione di test");
		OpzionePagamento opzione = new OpzionePagamento();
		opzione.setStato(statoOpzione);
		opzione.setDataScadenza(dataScadenzaOpzione);
		posizione.addOpzionePagamento(opzione);
		for (Pendenza pendenza : pendenze) {
			opzione.addPendenza(pendenza);
		}
		return posizione;
	}

	private Pendenza pendenza(StatoPendenza stato, OffsetDateTime dataScadenzaAvviso, double importo) {
		Pendenza pendenza = new Pendenza();
		pendenza.setStato(stato);
		pendenza.setDataScadenzaAvviso(dataScadenzaAvviso);
		pendenza.setImporto(importo);
		pendenza.setNumeroAvviso("300000000000000001");
		pendenza.setIuv("300000000000000001");
		return pendenza;
	}

	@Test
	void opzioneAnnullataNonCompareInRisposta() {
		PosizioneDebitoria posizione = posizioneConUnaOpzione(StatoOpzionePagamento.ANNULLATA, null,
				pendenza(StatoPendenza.ANNULLATO, null, 10));

		assertThat(OpzionePagamentoResponseMapper.opzioniRestituibili(posizione, CLOCK)).isEmpty();
	}

	@Test
	void disponibileNonScadutaEPendenzaNonScaduta() {
		PosizioneDebitoria posizione = posizioneConUnaOpzione(StatoOpzionePagamento.DISPONIBILE, null,
				pendenza(StatoPendenza.NON_ESEGUITO, DOMANI, 10));

		PaymentOption opzione = unicaOpzione(posizione);
		assertThat(opzione.getStatus()).isEqualTo(EnumPo.PO_UNPAID);
		assertThat(opzione.getInstallments().get(0).getStatus()).isEqualTo(EnumInstallment.POI_UNPAID);
	}

	@Test
	void disponibileScadutaEPendenzaScaduta() {
		PosizioneDebitoria posizione = posizioneConUnaOpzione(StatoOpzionePagamento.DISPONIBILE, LocalDate.now(CLOCK).minusDays(1),
				pendenza(StatoPendenza.NON_ESEGUITO, IERI, 10));

		PaymentOption opzione = unicaOpzione(posizione);
		assertThat(opzione.getStatus()).isEqualTo(EnumPo.PO_EXPIRED_UNPAID);
		assertThat(opzione.getInstallments().get(0).getStatus()).isEqualTo(EnumInstallment.POI_EXPIRED_UNPAID);
	}

	@Test
	void attivataConTutteLePendenzePagateEPagato() {
		PosizioneDebitoria posizione = posizioneConUnaOpzione(StatoOpzionePagamento.ATTIVATA, null,
				pendenza(StatoPendenza.ESEGUITO, null, 10), pendenza(StatoPendenza.INCASSATO, null, 10));

		PaymentOption opzione = unicaOpzione(posizione);
		assertThat(opzione.getStatus()).isEqualTo(EnumPo.PO_PAID);
		assertThat(opzione.getInstallments()).extracting(i -> i.getStatus())
				.containsExactly(EnumInstallment.POI_PAID, EnumInstallment.POI_PAID);
	}

	@Test
	void attivataConRateMisteEParzialmentePagato() {
		PosizioneDebitoria posizione = posizioneConUnaOpzione(StatoOpzionePagamento.ATTIVATA, null,
				pendenza(StatoPendenza.ESEGUITO, null, 10), pendenza(StatoPendenza.NON_ESEGUITO, DOMANI, 10));

		PaymentOption opzione = unicaOpzione(posizione);
		assertThat(opzione.getStatus()).isEqualTo(EnumPo.PO_PARTIALLY_PAID);
	}

	@Test
	void parzialmenteEseguitoMappaSuNonPagata() {
		PosizioneDebitoria posizione = posizioneConUnaOpzione(StatoOpzionePagamento.DISPONIBILE, null,
				pendenza(StatoPendenza.PARZIALMENTE_ESEGUITO, DOMANI, 10));

		assertThat(unicaOpzione(posizione).getInstallments().get(0).getStatus()).isEqualTo(EnumInstallment.POI_UNPAID);
	}

	@Test
	void annullataOAnomalaMappaSuInvalida() {
		PosizioneDebitoria posizione = posizioneConUnaOpzione(StatoOpzionePagamento.DISPONIBILE, null,
				pendenza(StatoPendenza.ANOMALO, null, 10));

		assertThat(unicaOpzione(posizione).getInstallments().get(0).getStatus()).isEqualTo(EnumInstallment.POI_INVALID);
	}

	@Test
	void amountDiOpzioneESommaDegliInstallmentInCentesimi() {
		PosizioneDebitoria posizione = posizioneConUnaOpzione(StatoOpzionePagamento.DISPONIBILE, null,
				pendenza(StatoPendenza.NON_ESEGUITO, DOMANI, 40.5), pendenza(StatoPendenza.NON_ESEGUITO, DOMANI, 10));

		PaymentOption opzione = unicaOpzione(posizione);
		assertThat(opzione.getAmount()).isEqualTo(5050L);
		assertThat(opzione.getInstallments()).extracting(i -> i.getAmount()).containsExactly(4050L, 1000L);
	}

	private PaymentOption unicaOpzione(PosizioneDebitoria posizione) {
		List<PaymentOption> opzioni = OpzionePagamentoResponseMapper.opzioniRestituibili(posizione, CLOCK);
		assertThat(opzioni).hasSize(1);
		return opzioni.get(0);
	}
}
