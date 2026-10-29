package it.govpay.pafornode.rpt;

import java.math.BigDecimal;
import java.util.Comparator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferListPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.StTransferType;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.CtMapEntry;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.CtMetadata;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.service.CausaleVersamentoCodec;

/**
 * {@code CtPaymentPA} dell'operazione {@code paGetPayment} (V1), analisi completa del
 * legacy ({@code CtPaymentPABuilder}) nel design doc. A differenza di V2 (da implementare),
 * non supporta la Marca da Bollo Telematica (rifiutata a monte dall'endpoint) ne' metadati
 * di contabilita'/custom per transfer — nessuna delle due e' prevista dal contratto V1.
 */
@Component
public class CtPaymentPaBuilder {

	public static final String NOTIFICATION_FEE_METADATA_KEY = "NOTIFICATION_FEE";

	private final TributoRepository tributoRepository;
	private final IbanAccreditoRepository ibanAccreditoRepository;
	private final UnitaOperativaRepository unitaOperativaRepository;
	private final Integer numeroGiorniValiditaPendenza;

	public CtPaymentPaBuilder(TributoRepository tributoRepository, IbanAccreditoRepository ibanAccreditoRepository,
			UnitaOperativaRepository unitaOperativaRepository,
			@Value("${govpay.pafornode.numero-giorni-validita-pendenza:#{null}}") Integer numeroGiorniValiditaPendenza) {
		this.tributoRepository = tributoRepository;
		this.ibanAccreditoRepository = ibanAccreditoRepository;
		this.unitaOperativaRepository = unitaOperativaRepository;
		this.numeroGiorniValiditaPendenza = numeroGiorniValiditaPendenza;
	}

	public CtPaymentPA costruisci(Pendenza pendenza, DominioEntity dominio, StTransferType transferType) {
		PosizioneDebitoria posizione = pendenza.getOpzionePagamento().getPosizioneDebitoria();
		String causale = CausaleVersamentoCodec.decode(pendenza.getCausaleVersamento());
		String descrizione = causale != null ? causale : posizione.getDescrizione();

		CtPaymentPA ctPayment = new CtPaymentPA();
		ctPayment.setCreditorReferenceId(pendenza.getIuv());
		ctPayment.setPaymentAmount(importoTotaleConSend(pendenza));
		ctPayment.setDueDate(XmlDateUtils.toXmlDate(DueDateCalculator.calcola(pendenza, numeroGiorniValiditaPendenza)));
		// Un'unica opzione di pagamento gestita: sempre l'ultima (e unica) rata.
		ctPayment.setLastPayment(true);
		ctPayment.setDescription(descrizione != null ? descrizione : " ");
		ctPayment.setCompanyName(dominio.getRagioneSociale());
		ctPayment.setOfficeName(UnitaOperativaResolver.risolviNome(unitaOperativaRepository, posizione));
		ctPayment.setDebtor(DebitoreBuilder.costruisci(debitorePrincipale(posizione)));

		CtTransferListPA transferList = new CtTransferListPA();
		ctPayment.setTransferList(transferList);

		boolean richiestoIbanPostale = transferType == StTransferType.POSTAL;
		boolean quotaSendAssegnata = false;
		int indice = 1;
		for (VocePendenza voce : pendenza.getVoci()) {
			CtTransferPA transfer = new CtTransferPA();
			transfer.setIdTransfer(indice);

			boolean transferStessoEnte = voce.getIdDominio() == null || voce.getIdDominio().equals(dominio.getId());
			boolean applicaQuotaSend = !quotaSendAssegnata && transferStessoEnte
					&& pendenza.getSendImportoTotale() != null;

			BigDecimal importoTransfer = BigDecimal.valueOf(voce.getImporto());
			if (applicaQuotaSend) {
				importoTransfer = importoTransfer.add(BigDecimal.valueOf(pendenza.getSendImportoTotale()));
				quotaSendAssegnata = true;
			}
			transfer.setTransferAmount(importoTransfer);

			TributoEntity tributo = voce.getIdTributo() != null
					? tributoRepository.findById(voce.getIdTributo()).orElse(null) : null;

			IbanAccreditoResolver.Coppia coppia = IbanAccreditoResolver.risolvi(ibanAccreditoRepository, voce,
					tributo);
			IbanAccreditoEntity ibanScelto = IbanAccreditoResolver.scegli(coppia, indice == 1, richiestoIbanPostale);
			if (ibanScelto != null) {
				transfer.setIBAN(ibanScelto.getCodIban());
				transfer.setFiscalCodePA(ibanScelto.getDominio().getCodDominio());
			}

			transfer.setRemittanceInformation(RemittanceInformationBuilder.costruisci(pendenza.getIuv(),
					BigDecimal.valueOf(voce.getImporto()), voce.getDescrizione(), voce.getDescrizioneCausaleRpt()));
			transfer.setTransferCategory(ContabilitaResolver.transferCategory(voce, tributo));

			if (applicaQuotaSend) {
				transfer.setMetadata(metadataSpeseNotifica(pendenza.getSendImportoTotale()));
			}

			transferList.getTransfer().add(transfer);
			indice++;
		}

		if (pendenza.getSendImportoTotale() != null && !quotaSendAssegnata) {
			throw new IllegalStateException("Impossibile attribuire la quota di spese di notifica SEND: nessun "
					+ "transfer della pendenza [dominio:" + dominio.getCodDominio() + ", iuv:" + pendenza.getIuv()
					+ "] risulta di competenza dell'ente creditore.");
		}

		return ctPayment;
	}

	private static SoggettoDebitore debitorePrincipale(PosizioneDebitoria posizione) {
		return posizione.getSoggettiDebitori().stream().min(Comparator.comparingInt(SoggettoDebitore::getOrdine))
				.orElseThrow(() -> new IllegalStateException("Posizione debitoria senza soggetti debitori."));
	}

	private static BigDecimal importoTotaleConSend(Pendenza pendenza) {
		BigDecimal importo = BigDecimal.valueOf(pendenza.getImporto());
		return pendenza.getSendImportoTotale() != null ? importo.add(BigDecimal.valueOf(pendenza.getSendImportoTotale()))
				: importo;
	}

	private static CtMetadata metadataSpeseNotifica(double sendImportoTotale) {
		CtMetadata metadata = new CtMetadata();
		CtMapEntry speseNotifica = new CtMapEntry();
		speseNotifica.setKey(NOTIFICATION_FEE_METADATA_KEY);
		speseNotifica.setValue(BigDecimal.valueOf(sendImportoTotale).movePointRight(2).toBigInteger().toString());
		metadata.getMapEntry().add(speseNotifica);
		return metadata;
	}
}
