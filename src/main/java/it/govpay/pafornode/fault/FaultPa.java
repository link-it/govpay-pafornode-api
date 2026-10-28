package it.govpay.pafornode.fault;

/**
 * Vocabolario dei fault di protocollo pagoPA per le interfacce paForNode (non specifico di
 * GovPay). Porting letterale di {@code it.govpay.core.exceptions.NdpException.FaultPa} del
 * monolite — stesso elenco, stesse stringhe.
 */
public enum FaultPa {

	PAA_ATTIVA_RPT_IMPORTO_NON_VALIDO("L'importo del pagamento in attesa non è congruente con il dato indicato dal PSP"),
	PAA_ER_DUPLICATA("La ER è già stata accettata."),
	PAA_ERRORE_FORMATO_BUSTA_FIRMATA("Formato busta di firma errato o non corrispondente al tipoFirma."),
	PAA_FIRMA_ERRATA("Errore di firma."),
	PAA_FIRMA_INDISPONIBILE("Impossibile firmare."),
	PAA_ID_DOMINIO_ERRATO("La PAA non corrisponde al Dominio indicato."),
	PAA_ID_INTERMEDIARIO_ERRATO("Identificativo intermediario non corrispondente."),
	PAA_PAGAMENTO_ANNULLATO("Pagamento in attesa risulta annullato all'Ente Creditore."),
	PAA_PAGAMENTO_DUPLICATO("Pagamento in attesa risulta concluso all'Ente Creditore."),
	PAA_PAGAMENTO_IN_CORSO("Pagamento in attesa risulta in corso all'Ente Creditore."),
	PAA_PAGAMENTO_SCADUTO("Pagamento in attesa risulta scaduto all'Ente Creditore."),
	PAA_PAGAMENTO_SCONOSCIUTO("Pagamento in attesa risulta sconosciuto all’Ente Creditore."),
	PAA_RPT_SCONOSCIUTA("La RPT risulta sconosciuta."),
	PAA_RT_DUPLICATA("La RT è già stata accettata."),
	PAA_RECEIPT_DUPLICATA("La CtReceipt è già stata accettata."),
	PAA_SEMANTICA("Errore semantico."),
	PAA_SINTASSI_EXTRAXSD("Errore di sintassi extra XSD."),
	PAA_SINTASSI_XSD("Errore di sintassi XSD."),
	PAA_STAZIONE_INT_ERRATA("Stazione intermediario non corrispondente."),
	PAA_SYSTEM_ERROR("Errore generico."),
	PAA_TIPOFIRMA_SCONOSCIUTO("Il campo tipoFirma non corrisponde ad alcun valore previsto."),
	PAA_PAGAMENTO_MULTIBENEFICIARIO_NON_CONSENTITO(
			"Pagamento multibeneficiario non consentito con le specifiche SANP-SPC 2.3.0.");

	private final String faultString;

	FaultPa(String faultString) {
		this.faultString = faultString;
	}

	public String getFaultString() {
		return faultString;
	}
}
