package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.repository.PagamentoRepository;

/**
 * Verifica in particolare i vincoli di validita' dell'opzione v3 (dataInizioValidita/
 * dataScadenza), assenti nel legacy (il modello "opzione di pagamento" non esisteva) ma
 * necessari per non attivare un pagamento prima dell'apertura o dopo la chiusura
 * dell'opzione quando la scadenza della pendenza stessa non lo impedisce.
 */
class PaForNodeChecksTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneId.of("UTC"));
	private final PagamentoRepository pagamentoRepository = mock(PagamentoRepository.class);

	private Pendenza pendenzaConOpzione(LocalDate dataInizioValidita, LocalDate dataScadenza) {
		OpzionePagamento opzione = new OpzionePagamento();
		opzione.setDataInizioValidita(dataInizioValidita);
		opzione.setDataScadenza(dataScadenza);

		Pendenza pendenza = new Pendenza();
		pendenza.setIuv("01234567890123456789");
		pendenza.setStato(StatoPendenza.NON_ESEGUITO);
		opzione.addPendenza(pendenza);
		return pendenza;
	}

	@Test
	void rifiutaSeLopzioneNonEAncoraAperta() {
		Pendenza pendenza = pendenzaConOpzione(LocalDate.of(2026, 7, 1), null);

		assertThatThrownBy(
				() -> PaForNodeChecks.verificaStatoPagabile(pendenza, "12345678901", CLOCK, pagamentoRepository))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO);
	}

	@Test
	void rifiutaSeLopzioneEGiaChiusa() {
		Pendenza pendenza = pendenzaConOpzione(null, LocalDate.of(2026, 1, 1));

		assertThatThrownBy(
				() -> PaForNodeChecks.verificaStatoPagabile(pendenza, "12345678901", CLOCK, pagamentoRepository))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCADUTO);
	}

	@Test
	void procedeSeLopzioneENellaFinestraDiValidita() {
		Pendenza pendenza = pendenzaConOpzione(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
		when(pagamentoRepository.findByCodDominioAndIuv("12345678901", pendenza.getIuv())).thenReturn(List.of());

		PaForNodeChecks.verificaStatoPagabile(pendenza, "12345678901", CLOCK, pagamentoRepository);
		// Nessuna eccezione: la pendenza e' pagabile.
		assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.NON_ESEGUITO);
	}

	@Test
	void procedeSeLopzioneNonHaLimitiDiValidita() {
		Pendenza pendenza = pendenzaConOpzione(null, null);

		PaForNodeChecks.verificaStatoPagabile(pendenza, "12345678901", CLOCK, pagamentoRepository);
		assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.NON_ESEGUITO);
	}

	/**
	 * {@code dataScadenzaAvviso} e' sempre scritta a mezzanotte dal mapper (un giorno puro):
	 * un avviso che scade OGGI deve restare pagabile per tutta la giornata, non solo fino
	 * alla mezzanotte — confronto per giorno, non per istante corrente (bug riprodotto:
	 * prima del fix, {@code CLOCK} alle 10:00 UTC rifiutava una scadenza odierna a
	 * mezzanotte UTC).
	 */
	@Test
	void procedeSeLavvisoScadeOggi() {
		Pendenza pendenza = pendenzaConOpzione(null, null);
		pendenza.setDataScadenzaAvviso(LocalDate.now(CLOCK).atStartOfDay(CLOCK.getZone()).toOffsetDateTime());
		when(pagamentoRepository.findByCodDominioAndIuv("12345678901", pendenza.getIuv())).thenReturn(List.of());

		PaForNodeChecks.verificaStatoPagabile(pendenza, "12345678901", CLOCK, pagamentoRepository);
		assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.NON_ESEGUITO);
	}

	@Test
	void rifiutaSeLavvisoEScadutoIlGiornoPrecedente() {
		Pendenza pendenza = pendenzaConOpzione(null, null);
		pendenza.setDataScadenzaAvviso(
				LocalDate.now(CLOCK).minusDays(1).atStartOfDay(CLOCK.getZone()).toOffsetDateTime());

		assertThatThrownBy(
				() -> PaForNodeChecks.verificaStatoPagabile(pendenza, "12345678901", CLOCK, pagamentoRepository))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCADUTO);
	}
}
