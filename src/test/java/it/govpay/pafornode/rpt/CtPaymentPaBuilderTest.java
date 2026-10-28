package it.govpay.pafornode.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPA;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.TipoContabilita;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;

class CtPaymentPaBuilderTest {

	private final TributoRepository tributoRepository = mock(TributoRepository.class);
	private final IbanAccreditoRepository ibanAccreditoRepository = mock(IbanAccreditoRepository.class);
	private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);
	private final CtPaymentPaBuilder builder = new CtPaymentPaBuilder(tributoRepository, ibanAccreditoRepository,
			unitaOperativaRepository, null);

	private DominioEntity dominio() {
		DominioEntity dominio = DominioEntity.builder().codDominio("12345678901").ragioneSociale("Comune di Test")
				.build();
		dominio.setId(1L);
		return dominio;
	}

	private Pendenza pendenzaConUnaVoce(double importo, Long idTributo) {
		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setIdDominio(1L);
		posizione.setDescrizione("Descrizione posizione");
		SoggettoDebitore soggetto = new SoggettoDebitore();
		soggetto.setTipo(TipoSoggetto.F);
		soggetto.setIdentificativo("RSSMRA80A01H501U");
		soggetto.setAnagrafica("Mario Rossi");
		posizione.addSoggettoDebitore(soggetto);

		OpzionePagamento opzione = new OpzionePagamento();
		posizione.addOpzionePagamento(opzione);

		Pendenza pendenza = new Pendenza();
		pendenza.setIuv("01234567890123456789");
		pendenza.setImporto(importo);
		opzione.addPendenza(pendenza);

		VocePendenza voce = new VocePendenza();
		voce.setImporto(importo);
		voce.setDescrizione("voce di test");
		voce.setIndice(1);
		voce.setIdTributo(idTributo);
		voce.setTipoContabilita(TipoContabilita.CAPITOLO);
		voce.setCodiceContabilita("001");
		pendenza.addVocePendenza(voce);

		return pendenza;
	}

	@Test
	void mappaICampiPrincipaliEIlDebitore() {
		Pendenza pendenza = pendenzaConUnaVoce(16.00, null);

		CtPaymentPA ctPayment = builder.costruisci(pendenza, dominio(), null);

		assertThat(ctPayment.getCreditorReferenceId()).isEqualTo(pendenza.getIuv());
		assertThat(ctPayment.getPaymentAmount()).isEqualByComparingTo(BigDecimal.valueOf(16.00));
		assertThat(ctPayment.isLastPayment()).isTrue();
		assertThat(ctPayment.getCompanyName()).isEqualTo("Comune di Test");
		assertThat(ctPayment.getDebtor().getFullName()).isEqualTo("Mario Rossi");
		assertThat(ctPayment.getDebtor().getUniqueIdentifier().getEntityUniqueIdentifierValue())
				.isEqualTo("RSSMRA80A01H501U");
	}

	@Test
	void risolveIbanDalTributoQuandoLaVoceNeHaUno() {
		Pendenza pendenza = pendenzaConUnaVoce(10.00, 7L);

		IbanAccreditoEntity ibanAccredito = new IbanAccreditoEntity();
		ibanAccredito.setCodIban("IT00X0000000000000000000000");
		ibanAccredito.setPostale(false);
		DominioEntity dominioIban = dominio();
		ibanAccredito.setDominio(dominioIban);

		TributoEntity tributo = new TributoEntity();
		tributo.setIbanAccredito(ibanAccredito);
		when(tributoRepository.findById(7L)).thenReturn(Optional.of(tributo));

		CtPaymentPA ctPayment = builder.costruisci(pendenza, dominio(), null);

		CtTransferPA transfer = ctPayment.getTransferList().getTransfer().get(0);
		assertThat(transfer.getIBAN()).isEqualTo("IT00X0000000000000000000000");
		assertThat(transfer.getFiscalCodePA()).isEqualTo("12345678901");
		assertThat(transfer.getTransferCategory()).isEqualTo(TipoContabilita.CAPITOLO.getCodifica() + "/001");
	}

	@Test
	void recuperaTipoEcodiceContabilitaDalTributoQuandoAssentiSullaVoce() {
		Pendenza pendenza = pendenzaConUnaVoce(10.00, 7L);
		VocePendenza voce = pendenza.getVoci().get(0);
		voce.setTipoContabilita(null);
		voce.setCodiceContabilita(null);

		TributoEntity tributo = new TributoEntity();
		tributo.setTipoContabilita(TipoContabilita.SIOPE);
		tributo.setCodiceContabilita("999");
		when(tributoRepository.findById(7L)).thenReturn(Optional.of(tributo));

		CtPaymentPA ctPayment = builder.costruisci(pendenza, dominio(), null);

		CtTransferPA transfer = ctPayment.getTransferList().getTransfer().get(0);
		assertThat(transfer.getTransferCategory()).isEqualTo(TipoContabilita.SIOPE.getCodifica() + "/999");
	}

	@Test
	void recuperaDalTributoSoloIlCampoAssenteSullaVoceNonEntrambi() {
		// Stesso principio del legacy (SingoloVersamento#getTipoContabilita/#getCodContabilita):
		// due fallback indipendenti, non "se uno manca uso il tributo per entrambi".
		Pendenza pendenza = pendenzaConUnaVoce(10.00, 7L);
		VocePendenza voce = pendenza.getVoci().get(0);
		voce.setTipoContabilita(TipoContabilita.CAPITOLO);
		voce.setCodiceContabilita(null);

		TributoEntity tributo = new TributoEntity();
		tributo.setTipoContabilita(TipoContabilita.SIOPE);
		tributo.setCodiceContabilita("999");
		when(tributoRepository.findById(7L)).thenReturn(Optional.of(tributo));

		CtPaymentPA ctPayment = builder.costruisci(pendenza, dominio(), null);

		CtTransferPA transfer = ctPayment.getTransferList().getTransfer().get(0);
		assertThat(transfer.getTransferCategory()).isEqualTo(TipoContabilita.CAPITOLO.getCodifica() + "/999");
	}

	@Test
	void applicaLaQuotaSendAlTransferDelloStessoEnteEAggiungeIMetadata() {
		Pendenza pendenza = pendenzaConUnaVoce(10.00, null);
		pendenza.setSendImportoTotale(1.23);

		CtPaymentPA ctPayment = builder.costruisci(pendenza, dominio(), null);

		assertThat(ctPayment.getPaymentAmount()).isEqualByComparingTo(BigDecimal.valueOf(11.23));
		CtTransferPA transfer = ctPayment.getTransferList().getTransfer().get(0);
		assertThat(transfer.getTransferAmount()).isEqualByComparingTo(BigDecimal.valueOf(11.23));
		assertThat(transfer.getMetadata().getMapEntry()).hasSize(1);
		assertThat(transfer.getMetadata().getMapEntry().get(0).getKey())
				.isEqualTo(CtPaymentPaBuilder.NOTIFICATION_FEE_METADATA_KEY);
		assertThat(transfer.getMetadata().getMapEntry().get(0).getValue()).isEqualTo("123");
	}

	@Test
	void fallisceSeLaQuotaSendNonTrovaUnTransferDiCompetenza() {
		Pendenza pendenza = pendenzaConUnaVoce(10.00, null);
		pendenza.getVoci().get(0).setIdDominio(999L);
		pendenza.setSendImportoTotale(1.23);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.costruisci(pendenza, dominio(), null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("SEND");
	}
}
