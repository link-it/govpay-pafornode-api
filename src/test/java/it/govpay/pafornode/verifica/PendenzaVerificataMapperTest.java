package it.govpay.pafornode.verifica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.common.repository.TipoTributoRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.ec.client.beans.NuovaPendenza;
import it.govpay.ec.client.beans.NuovaVocePendenza;
import it.govpay.ec.client.beans.Soggetto;
import it.govpay.ec.client.beans.TipoRiferimentoVocePendenza;
import it.govpay.ec.client.beans.TipoSoggetto;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pendenze.comando.DatiPendenza;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.comando.DatiVocePendenza;

/**
 * {@code toDatiVocePendenza} (ramo IBAN diretto, tassonomia) e
 * {@code verificaIdentita}/completamento NAV di {@link PendenzaVerificataMapper} — gli altri
 * metodi sono esercitati indirettamente dai test di integrazione di
 * {@code PaForNodeEndpointTest}/{@code AttualizzazionePendenzaServiceTest}.
 */
class PendenzaVerificataMapperTest {

	private static final Long ID_DOMINIO = 1L;
	private static final String COD_DOMINIO = "12345678901";
	private static final String NUMERO_AVVISO_RICHIESTO = "302000100000019421";

	private final TipoTributoRepository tipoTributoRepository = mock(TipoTributoRepository.class);
	private final TributoRepository tributoRepository = mock(TributoRepository.class);
	private final IbanAccreditoRepository ibanAccreditoRepository = mock(IbanAccreditoRepository.class);
	private final TipoVersamentoDominioRepository tipoVersamentoDominioRepository = mock(
			TipoVersamentoDominioRepository.class);
	private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneId.of("UTC"));

	private final PendenzaVerificataMapper mapper = new PendenzaVerificataMapper(tipoTributoRepository,
			tributoRepository, ibanAccreditoRepository, tipoVersamentoDominioRepository, unitaOperativaRepository,
			clock);

	private NuovaPendenza nuovaPendenza(NuovaVocePendenza voce) {
		Soggetto soggetto = new Soggetto();
		soggetto.setTipo(TipoSoggetto.F);
		soggetto.setIdentificativo("RSSMRA80A01H501U");
		soggetto.setAnagrafica("Mario Rossi");

		NuovaPendenza dto = new NuovaPendenza();
		dto.setIdTipoPendenza("TARI");
		dto.setIdDominio(COD_DOMINIO);
		dto.setCausale("causale di test");
		dto.setSoggettoPagatore(soggetto);
		dto.setImporto(BigDecimal.TEN);
		dto.setNumeroAvviso(NUMERO_AVVISO_RICHIESTO);
		dto.setVoci(List.of(voce));
		return dto;
	}

	private void tipoVersamentoDominioPersistito() {
		TipoVersamentoEntity tipoVersamento = new TipoVersamentoEntity();
		tipoVersamento.setId(5L);
		TipoVersamentoDominioEntity tvd = new TipoVersamentoDominioEntity();
		tvd.setId(10L);
		tvd.setTipoVersamento(tipoVersamento);
		when(tipoVersamentoDominioRepository.findByCodTipoVersamentoAndDominioId("TARI", ID_DOMINIO))
				.thenReturn(Optional.of(tvd));
	}

	private DatiVocePendenza unicaVoce(DatiPosizioneDebitoria comando) {
		return comando.opzioniPagamento().get(0).pendenze().get(0).voci().get(0);
	}

	@Test
	void mappaLaVoceConIbanDirettoEConservaLaTassonomia() {
		tipoVersamentoDominioPersistito();
		NuovaVocePendenza voce = new NuovaVocePendenza();
		voce.setIdVocePendenza("voce-1");
		voce.setImporto(BigDecimal.TEN);
		voce.setDescrizione("voce con IBAN diretto");
		voce.setIbanAccredito("IT60X0542811101000000123456");
		voce.setCodiceTassonomicoPagoPA("9/3321");

		IbanAccreditoEntity iban = new IbanAccreditoEntity();
		iban.setId(42L);
		iban.setAbilitato(true);
		when(ibanAccreditoRepository.findByCodIbanAndDominioId("IT60X0542811101000000123456", ID_DOMINIO))
				.thenReturn(Optional.of(iban));

		DatiPosizioneDebitoria comando = mapper.creaDatiPosizioneDebitoria(nuovaPendenza(voce), 1L, ID_DOMINIO,
				NUMERO_AVVISO_RICHIESTO);

		DatiVocePendenza vocePendenza = unicaVoce(comando);
		assertThat(vocePendenza.idIbanAccredito()).isEqualTo(42L);
		assertThat(vocePendenza.idIbanAppoggio()).isNull();
		assertThat(vocePendenza.tassonomia()).isEqualTo("9/3321");
	}

	@Test
	void rifiutaLibanNonCensitoPerIlDominio() {
		tipoVersamentoDominioPersistito();
		NuovaVocePendenza voce = new NuovaVocePendenza();
		voce.setIdVocePendenza("voce-1");
		voce.setImporto(BigDecimal.TEN);
		voce.setDescrizione("voce con IBAN diretto");
		voce.setIbanAccredito("IT60X0542811101000000123456");
		voce.setCodiceTassonomicoPagoPA("9/3321");
		when(ibanAccreditoRepository.findByCodIbanAndDominioId("IT60X0542811101000000123456", ID_DOMINIO))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> mapper.creaDatiPosizioneDebitoria(nuovaPendenza(voce), 1L, ID_DOMINIO,
				NUMERO_AVVISO_RICHIESTO)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void mappaLaTassonomiaAncheSulRamoBollo() {
		tipoVersamentoDominioPersistito();
		NuovaVocePendenza voce = new NuovaVocePendenza();
		voce.setIdVocePendenza("voce-1");
		voce.setImporto(BigDecimal.TEN);
		voce.setDescrizione("marca da bollo");
		voce.setTipoBollo(TipoRiferimentoVocePendenza.TipoBolloEnum._01);
		voce.setHashDocumento("hash");
		voce.setProvinciaResidenza("RO");
		voce.setCodiceTassonomicoPagoPA("9/0101100IM");

		DatiPosizioneDebitoria comando = mapper.creaDatiPosizioneDebitoria(nuovaPendenza(voce), 1L, ID_DOMINIO,
				NUMERO_AVVISO_RICHIESTO);

		DatiVocePendenza vocePendenza = unicaVoce(comando);
		assertThat(vocePendenza.tassonomia()).isEqualTo("9/0101100IM");
		assertThat(vocePendenza.tipoBollo()).isEqualTo("01");
	}

	@Test
	void completaIlNumeroAvvisoAssenteConQuelloRichiesto() {
		tipoVersamentoDominioPersistito();
		NuovaVocePendenza voce = new NuovaVocePendenza();
		voce.setIdVocePendenza("voce-1");
		voce.setImporto(BigDecimal.TEN);
		voce.setDescrizione("voce con IBAN diretto");
		voce.setIbanAccredito("IT60X0542811101000000123456");
		voce.setCodiceTassonomicoPagoPA("9/3321");
		when(ibanAccreditoRepository.findByCodIbanAndDominioId("IT60X0542811101000000123456", ID_DOMINIO))
				.thenReturn(Optional.of(new IbanAccreditoEntity()));

		NuovaPendenza dto = nuovaPendenza(voce);
		dto.setNumeroAvviso(null);

		DatiPosizioneDebitoria comando = mapper.creaDatiPosizioneDebitoria(dto, 1L, ID_DOMINIO,
				NUMERO_AVVISO_RICHIESTO);

		DatiPendenza datiPendenza = comando.opzioniPagamento().get(0).pendenze().get(0);
		assertThat(datiPendenza.numeroAvviso()).isEqualTo(NUMERO_AVVISO_RICHIESTO);
	}

	@Test
	void verificaIdentitaRifiutaUnDominioDiverso() {
		NuovaPendenza dto = nuovaPendenza(new NuovaVocePendenza());
		dto.setIdDominio("99999999999");

		assertThatThrownBy(() -> PendenzaVerificataMapper.verificaIdentita(dto, COD_DOMINIO, NUMERO_AVVISO_RICHIESTO,
				COD_DOMINIO)).isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO);
	}

	@Test
	void verificaIdentitaRifiutaUnNumeroAvvisoDiverso() {
		NuovaPendenza dto = nuovaPendenza(new NuovaVocePendenza());
		dto.setNumeroAvviso("999999999999999999");

		assertThatThrownBy(() -> PendenzaVerificataMapper.verificaIdentita(dto, COD_DOMINIO, NUMERO_AVVISO_RICHIESTO,
				COD_DOMINIO)).isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO);
	}

	@Test
	void verificaIdentitaProcedeSeIlNumeroAvvisoEAssente() {
		NuovaPendenza dto = nuovaPendenza(new NuovaVocePendenza());
		dto.setNumeroAvviso(null);

		PendenzaVerificataMapper.verificaIdentita(dto, COD_DOMINIO, NUMERO_AVVISO_RICHIESTO, COD_DOMINIO);
		// nessuna eccezione: il NAV assente non e' un mismatch, verra' completato a valle.
	}
}
