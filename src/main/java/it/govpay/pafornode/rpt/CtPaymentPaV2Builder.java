package it.govpay.pafornode.rpt;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferListPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.StTransferType;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.CtMapEntry;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.CtMetadata;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.CtRichiestaMarcaDaBollo;
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
import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.service.CausaleVersamentoCodec;

/**
 * {@code CtPaymentPAV2} dell'operazione {@code paGetPaymentV2}, analisi completa del legacy
 * ({@code CtPaymentPAV2Builder}) nel design doc. Stessa logica di base di {@link
 * CtPaymentPaBuilder} (V1) — stesso {@code DueDateCalculator}/{@code DebitoreBuilder}/{@code
 * RemittanceInformationBuilder}/{@code IbanAccreditoResolver}/{@code ContabilitaResolver},
 * verificati identici fra i due builder nel legacy — con due differenze:
 *
 * <ol>
 * <li>Marca da Bollo Telematica NON rifiutata a monte: una voce senza IBAN risolvibile (ne'
 * da tributo ne' diretto) diventa {@code CtRichiestaMarcaDaBollo} invece di un transfer con
 * IBAN, con fallback del tipo bollo a {@code "01"} ({@code TipoBollo.IMPOSTA_BOLLO} legacy,
 * unico valore esistente) se la voce non lo specifica.</li>
 * <li>{@code companyName} impostato anche sul singolo transfer (non solo sul pagamento),
 * sia nel ramo IBAN sia nel ramo bollo.</li>
 * </ol>
 *
 * <p>{@code transferCategory} si calcola allo stesso modo per ogni transfer, bollo
 * compreso ({@link ContabilitaResolver#transferCategory}): per il bollo la tassonomia
 * ({@code tipoContabilita}/{@code codiceContabilita}) e' l'unica classificazione ammessa
 * (nessun tributo), validata obbligatoria in scrittura da {@code
 * ValidatorePosizioneDebitoria} — un dato storico incompleto fallisce comunque qui con un
 * errore esplicito, non con una stringa incoerente.
 *
 * <p><b>Metadati di contabilita'/custom per transfer</b> da {@code
 * VocePendenza.getDettaglioContabile()}: non e' una replica del vecchio JSON legacy
 * ({@code QuotaContabilita}), che il modello v3-nativo ha deliberatamente sostituito (§14 di
 * {@code proposta-modello-nativo-v3.md} di govpay-common-pendenze) con il sealed interface
 * {@code DettaglioContabile}, mappato direttamente sul Dizionario dei metadata pagoPA
 * ufficiale (sezione "Riconciliazione contabile", issue #877 dello YAML v3) — le chiavi
 * {@code RC_*} sono gia' documentate nel Javadoc di ciascuna variante di
 * {@code DettaglioContabile}. {@code SpeseNotifica} usa la stessa chiave
 * {@code NOTIFICATION_FEE} della quota SEND automatica — le due vie sono mutuamente
 * esclusive per costruzione dei dati (se una voce ha {@code SpeseNotifica},
 * {@code pendenza.isSendAbilitato()} e' necessariamente false, quindi
 * {@code pendenza.getSendImportoTotale()} resta null e la quota automatica non scatta mai su
 * nessun transfer — verificato in {@code ValidatorePosizioneDebitoria}, non ri-verificato
 * qui). {@code RC_IMP}/{@code NOTIFICATION_FEE} in centesimi, stesso formato intero.
 */
@Component
public class CtPaymentPaV2Builder {

	private final TributoRepository tributoRepository;
	private final IbanAccreditoRepository ibanAccreditoRepository;
	private final UnitaOperativaRepository unitaOperativaRepository;
	private final Integer numeroGiorniValiditaPendenza;

	public CtPaymentPaV2Builder(TributoRepository tributoRepository, IbanAccreditoRepository ibanAccreditoRepository,
			UnitaOperativaRepository unitaOperativaRepository,
			@Value("${govpay.pafornode.numero-giorni-validita-pendenza:#{null}}") Integer numeroGiorniValiditaPendenza) {
		this.tributoRepository = tributoRepository;
		this.ibanAccreditoRepository = ibanAccreditoRepository;
		this.unitaOperativaRepository = unitaOperativaRepository;
		this.numeroGiorniValiditaPendenza = numeroGiorniValiditaPendenza;
	}

	public CtPaymentPAV2 costruisci(Pendenza pendenza, DominioEntity dominio, StTransferType transferType) {
		PosizioneDebitoria posizione = pendenza.getOpzionePagamento().getPosizioneDebitoria();
		String causale = CausaleVersamentoCodec.decode(pendenza.getCausaleVersamento());
		String descrizione = causale != null ? causale : posizione.getDescrizione();

		CtPaymentPAV2 ctPayment = new CtPaymentPAV2();
		ctPayment.setCreditorReferenceId(pendenza.getIuv());
		ctPayment.setPaymentAmount(importoTotaleConSend(pendenza));
		ctPayment.setDueDate(XmlDateUtils.toXmlDate(DueDateCalculator.calcola(pendenza, numeroGiorniValiditaPendenza)));
		// Un'unica opzione di pagamento gestita: sempre l'ultima (e unica) rata.
		ctPayment.setLastPayment(true);
		ctPayment.setDescription(descrizione != null ? descrizione : " ");
		ctPayment.setCompanyName(dominio.getRagioneSociale());
		ctPayment.setOfficeName(UnitaOperativaResolver.risolviNome(unitaOperativaRepository, posizione));
		ctPayment.setDebtor(DebitoreBuilder.costruisci(debitorePrincipale(posizione)));

		CtTransferListPAV2 transferList = new CtTransferListPAV2();
		ctPayment.setTransferList(transferList);

		boolean richiestoIbanPostale = transferType == StTransferType.POSTAL;
		boolean quotaSendAssegnata = false;
		int indice = 1;
		for (VocePendenza voce : pendenza.getVoci()) {
			CtTransferPAV2 transfer = new CtTransferPAV2();
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
				transfer.setCompanyName(ibanScelto.getDominio().getRagioneSociale());
			} else {
				transfer.setRichiestaMarcaDaBollo(marcaDaBollo(voce));
				transfer.setFiscalCodePA(dominio.getCodDominio());
				transfer.setCompanyName(dominio.getRagioneSociale());
			}

			transfer.setRemittanceInformation(RemittanceInformationBuilder.costruisci(pendenza.getIuv(),
					BigDecimal.valueOf(voce.getImporto()), voce.getDescrizione(), voce.getDescrizioneCausaleRpt()));
			transfer.setTransferCategory(ContabilitaResolver.transferCategory(voce, tributo));

			CtMetadata metadata = metadataDettaglioContabile(voce.getDettaglioContabile());
			if (applicaQuotaSend) {
				metadata = aggiungiQuotaSend(metadata, pendenza.getSendImportoTotale());
			}
			transfer.setMetadata(metadata);

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

	/** Fallback tipo bollo {@code "01"}: unico valore legacy ({@code TipoBollo.IMPOSTA_BOLLO}). */
	private static CtRichiestaMarcaDaBollo marcaDaBollo(VocePendenza voce) {
		CtRichiestaMarcaDaBollo marcaBollo = new CtRichiestaMarcaDaBollo();
		if (voce.getHashDocumento() != null) {
			marcaBollo.setHashDocumento(Base64.getDecoder().decode(voce.getHashDocumento()));
		}
		marcaBollo.setProvinciaResidenza(voce.getProvinciaResidenza());
		marcaBollo.setTipoBollo(voce.getTipoBollo() != null ? voce.getTipoBollo() : "01");
		return marcaBollo;
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

	/**
	 * Mappatura dei dettagli di riconciliazione contabile (Dizionario dei metadata pagoPA,
	 * chiavi {@code RC_*} documentate nel Javadoc di {@link DettaglioContabile}) — {@code
	 * null} se la voce non ne ha nessuno.
	 */
	private static CtMetadata metadataDettaglioContabile(List<DettaglioContabile> dettagli) {
		if (dettagli == null || dettagli.isEmpty()) {
			return null;
		}
		CtMetadata metadata = new CtMetadata();
		for (DettaglioContabile dettaglio : dettagli) {
			aggiungiEntries(metadata, dettaglio);
		}
		return metadata.getMapEntry().isEmpty() ? null : metadata;
	}

	private static void aggiungiEntries(CtMetadata metadata, DettaglioContabile dettaglio) {
		switch (dettaglio) {
			case DettaglioContabile.CorrispettivoDl118 d -> {
				entry(metadata, "RC_AC", d.annoCompetenza());
				entry(metadata, "RC_CU", d.codiceUfficio());
				entry(metadata, "RC_CAP", d.capitolo());
				entry(metadata, "RC_ACC", d.accertamento());
				entry(metadata, "RC_ART", d.articolo());
				entry(metadata, "RC_PF5", d.pianoFinanziario5Livello());
				entryImportoCentesimi(metadata, "RC_IMP", d.importo());
			}
			case DettaglioContabile.IncassoTipico d -> {
				entry(metadata, "RC_AC", d.annoCompetenza());
				entry(metadata, "RC_CU", d.codiceUfficio());
				entry(metadata, "RC_TI", d.tipoIncasso());
				entry(metadata, "RC_EF", d.emissioneFattura());
				entry(metadata, "RC_ND", d.nrDocumento());
				entryImportoCentesimi(metadata, "RC_IMP", d.importo());
			}
			case DettaglioContabile.Civilistico d -> {
				entry(metadata, "RC_AC", d.annoCompetenza());
				entry(metadata, "RC_CU", d.codiceUfficio());
				entry(metadata, "RC_CON", d.conto());
				entry(metadata, "RC_COM", d.commessa());
				entry(metadata, "RC_ND", d.nrDocumento());
				entryImportoCentesimi(metadata, "RC_IMP", d.importo());
			}
			case DettaglioContabile.SpeseNotifica d ->
					entryImportoCentesimi(metadata, CtPaymentPaBuilder.NOTIFICATION_FEE_METADATA_KEY, d.importo());
			case DettaglioContabile.Sconosciuto d -> {
				for (DettaglioContabile.Sconosciuto.Voce voce : d.entries()) {
					entry(metadata, voce.chiave(), voce.valore());
				}
			}
		}
	}

	private static CtMetadata aggiungiQuotaSend(CtMetadata metadata, double sendImportoTotale) {
		CtMetadata risultato = metadata != null ? metadata : new CtMetadata();
		entryImportoCentesimi(risultato, CtPaymentPaBuilder.NOTIFICATION_FEE_METADATA_KEY,
				BigDecimal.valueOf(sendImportoTotale));
		return risultato;
	}

	private static void entry(CtMetadata metadata, String key, String value) {
		if (value == null) {
			return;
		}
		CtMapEntry mapEntry = new CtMapEntry();
		mapEntry.setKey(key);
		mapEntry.setValue(value);
		metadata.getMapEntry().add(mapEntry);
	}

	private static void entryImportoCentesimi(CtMetadata metadata, String key, BigDecimal importo) {
		if (importo == null) {
			return;
		}
		CtMapEntry mapEntry = new CtMapEntry();
		mapEntry.setKey(key);
		mapEntry.setValue(importo.movePointRight(2).toBigInteger().toString());
		metadata.getMapEntry().add(mapEntry);
	}
}
