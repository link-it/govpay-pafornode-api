package it.govpay.pafornode.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAV2;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.CtMapEntry;
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
import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.model.TipoSoggetto;

class CtPaymentPaV2BuilderTest {

	private final TributoRepository tributoRepository = mock(TributoRepository.class);
	private final IbanAccreditoRepository ibanAccreditoRepository = mock(IbanAccreditoRepository.class);
	private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);
	private final CtPaymentPaV2Builder builder = new CtPaymentPaV2Builder(tributoRepository, ibanAccreditoRepository,
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

		CtPaymentPAV2 ctPayment = builder.costruisci(pendenza, dominio(), null);

		CtTransferPAV2 transfer = ctPayment.getTransferList().getTransfer().get(0);
		assertThat(transfer.getIBAN()).isEqualTo("IT00X0000000000000000000000");
		assertThat(transfer.getFiscalCodePA()).isEqualTo("12345678901");
		assertThat(transfer.getCompanyName()).isEqualTo("Comune di Test");
		assertThat(transfer.getRichiestaMarcaDaBollo()).isNull();
	}

	@Test
	void unaVoceSenzaIbanRisolvibileDiventaMarcaDaBollo() {
		Pendenza pendenza = pendenzaConUnaVoce(16.00, null);
		VocePendenza voce = pendenza.getVoci().get(0);
		voce.setHashDocumento(Base64.getEncoder().encodeToString("hash-documento".getBytes()));
		voce.setProvinciaResidenza("RM");
		voce.setTipoBollo("01");

		CtPaymentPAV2 ctPayment = builder.costruisci(pendenza, dominio(), null);

		CtTransferPAV2 transfer = ctPayment.getTransferList().getTransfer().get(0);
		assertThat(transfer.getIBAN()).isNull();
		assertThat(transfer.getRichiestaMarcaDaBollo()).isNotNull();
		assertThat(transfer.getRichiestaMarcaDaBollo().getHashDocumento())
				.isEqualTo("hash-documento".getBytes());
		assertThat(transfer.getRichiestaMarcaDaBollo().getProvinciaResidenza()).isEqualTo("RM");
		assertThat(transfer.getRichiestaMarcaDaBollo().getTipoBollo()).isEqualTo("01");
		assertThat(transfer.getFiscalCodePA()).isEqualTo("12345678901");
		assertThat(transfer.getCompanyName()).isEqualTo("Comune di Test");
	}

	@Test
	void unaVoceBolloSenzaTipoBolloUsaIlFallbackImpostaBollo() {
		Pendenza pendenza = pendenzaConUnaVoce(16.00, null);
		VocePendenza voce = pendenza.getVoci().get(0);
		voce.setProvinciaResidenza("RM");
		voce.setTipoBollo(null);

		CtPaymentPAV2 ctPayment = builder.costruisci(pendenza, dominio(), null);

		CtTransferPAV2 transfer = ctPayment.getTransferList().getTransfer().get(0);
		assertThat(transfer.getRichiestaMarcaDaBollo().getTipoBollo()).isEqualTo("01");
	}

	/**
	 * Per il bollo la tassonomia (qui azzerata apposta, a differenza del resto della
	 * fixture) e' l'unica classificazione ammessa, non un tributo — vedi Javadoc di
	 * {@link ContabilitaResolver#transferCategory}.
	 */
	@Test
	void unaVoceBolloSenzaTassonomiaFalliscConErroreEsplicitoNonConNpe() {
		Pendenza pendenza = pendenzaConUnaVoce(16.00, null);
		VocePendenza voce = pendenza.getVoci().get(0);
		voce.setHashDocumento(Base64.getEncoder().encodeToString("hash-documento".getBytes()));
		voce.setProvinciaResidenza("RM");
		voce.setTipoBollo("01");
		voce.setTipoContabilita(null);
		voce.setCodiceContabilita(null);

		assertThatThrownBy(() -> builder.costruisci(pendenza, dominio(), null))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void applicaLaQuotaSendAlTransferDelloStessoEnteEAggiungeIMetadata() {
		Pendenza pendenza = pendenzaConUnaVoce(10.00, null);
		pendenza.setSendImportoTotale(1.23);

		CtPaymentPAV2 ctPayment = builder.costruisci(pendenza, dominio(), null);

		assertThat(ctPayment.getPaymentAmount()).isEqualByComparingTo(BigDecimal.valueOf(11.23));
		CtTransferPAV2 transfer = ctPayment.getTransferList().getTransfer().get(0);
		assertThat(transfer.getTransferAmount()).isEqualByComparingTo(BigDecimal.valueOf(11.23));
		assertThat(transfer.getMetadata().getMapEntry()).hasSize(1);
		assertThat(transfer.getMetadata().getMapEntry().get(0).getKey())
				.isEqualTo(CtPaymentPaBuilder.NOTIFICATION_FEE_METADATA_KEY);
		assertThat(transfer.getMetadata().getMapEntry().get(0).getValue()).isEqualTo("123");
	}

	@Test
	void mappaUnCorrispettivoDl118SulleChiaviRcDelDizionarioPagoPA() {
		Pendenza pendenza = pendenzaConUnaVoce(10.00, null);
		VocePendenza voce = pendenza.getVoci().get(0);
		voce.setDettaglioContabile(List.of(new DettaglioContabile.CorrispettivoDl118("2026", "UFF01", "CAP01", null,
				null, null, BigDecimal.valueOf(10.00))));

		CtPaymentPAV2 ctPayment = builder.costruisci(pendenza, dominio(), null);

		List<CtMapEntry> entries = ctPayment.getTransferList().getTransfer().get(0).getMetadata().getMapEntry();
		assertThat(entries).extracting(CtMapEntry::getKey, CtMapEntry::getValue).containsExactlyInAnyOrder(
				tuple("RC_AC", "2026"), tuple("RC_CU", "UFF01"), tuple("RC_CAP", "CAP01"), tuple("RC_IMP", "1000"));
	}

	@Test
	void mappaSpeseNotificaSullaStessaChiaveDellaQuotaSendAutomatica() {
		Pendenza pendenza = pendenzaConUnaVoce(10.00, null);
		VocePendenza voce = pendenza.getVoci().get(0);
		voce.setDettaglioContabile(List.of(new DettaglioContabile.SpeseNotifica(BigDecimal.valueOf(1.23))));

		CtPaymentPAV2 ctPayment = builder.costruisci(pendenza, dominio(), null);

		List<CtMapEntry> entries = ctPayment.getTransferList().getTransfer().get(0).getMetadata().getMapEntry();
		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).getKey()).isEqualTo(CtPaymentPaBuilder.NOTIFICATION_FEE_METADATA_KEY);
		assertThat(entries.get(0).getValue()).isEqualTo("123");
	}

	@Test
	void mappaLeEntryGrezzeDiUnDettaglioSconosciuto() {
		Pendenza pendenza = pendenzaConUnaVoce(10.00, null);
		VocePendenza voce = pendenza.getVoci().get(0);
		voce.setDettaglioContabile(List.of(new DettaglioContabile.Sconosciuto(
				List.of(new DettaglioContabile.Sconosciuto.Voce("CHIAVE_X", "valore-x")))));

		CtPaymentPAV2 ctPayment = builder.costruisci(pendenza, dominio(), null);

		List<CtMapEntry> entries = ctPayment.getTransferList().getTransfer().get(0).getMetadata().getMapEntry();
		assertThat(entries).extracting(CtMapEntry::getKey, CtMapEntry::getValue)
				.containsExactly(tuple("CHIAVE_X", "valore-x"));
	}

	@Test
	void nessunMetadatoSeLaVoceNonHaDettagliContabiliNeQuotaSend() {
		Pendenza pendenza = pendenzaConUnaVoce(10.00, null);

		CtPaymentPAV2 ctPayment = builder.costruisci(pendenza, dominio(), null);

		assertThat(ctPayment.getTransferList().getTransfer().get(0).getMetadata()).isNull();
	}
}
