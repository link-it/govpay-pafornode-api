package it.govpay.pafornode.rpt;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtEntityUniqueIdentifier;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtSubject;
import it.gov.pagopa.pagopa_api.pa.pafornode.StEntityUniqueIdentifierType;
import it.govpay.pendenze.entity.SoggettoDebitore;

/** {@code CtSubject} del debitore principale, condiviso tra le operazioni paForNode che attivano una RPT. */
public final class DebitoreBuilder {

	private DebitoreBuilder() {
	}

	public static CtSubject costruisci(SoggettoDebitore debitore) {
		CtEntityUniqueIdentifier identificativo = new CtEntityUniqueIdentifier();
		String codiceIdentificativo = debitore.getIdentificativo();
		identificativo.setEntityUniqueIdentifierValue(codiceIdentificativo);
		identificativo.setEntityUniqueIdentifierType(
				codiceIdentificativo.length() == 16 ? StEntityUniqueIdentifierType.F : StEntityUniqueIdentifierType.G);

		CtSubject soggetto = new CtSubject();
		soggetto.setUniqueIdentifier(identificativo);
		soggetto.setFullName(debitore.getAnagrafica());
		soggetto.setStreetName(nonVuoto(debitore.getIndirizzo()));
		soggetto.setCivicNumber(nonVuoto(debitore.getCivico()));
		soggetto.setPostalCode(nonVuoto(debitore.getCap()));
		soggetto.setCity(nonVuoto(debitore.getLocalita()));
		soggetto.setStateProvinceRegion(nonVuoto(debitore.getProvincia()));
		soggetto.setCountry(nonVuoto(debitore.getNazione()));
		soggetto.setEMail(nonVuoto(debitore.getEmail()));
		return soggetto;
	}

	private static String nonVuoto(String testo) {
		return testo == null || testo.isBlank() ? null : testo;
	}
}
