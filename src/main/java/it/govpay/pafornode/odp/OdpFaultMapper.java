package it.govpay.pafornode.odp;

import java.util.Map;

import org.springframework.http.HttpStatus;

import it.govpay.pafornode.fault.FaultPa;

/**
 * Tabella {@link FaultPa} &rarr; (codice {@code ODP-xxx}, HTTP status) del contratto REST
 * "pagoPA Payment Options for Organizations" ({@code orgs_payment_options.json}, v0.7.0) —
 * mappatura separata da quella SOAP ({@code PaForNodeEndpoint#buildFault*}, che costruisce un
 * {@code CtFaultBean}), perche' qui lo status HTTP e il corpo {@code ErrorResponse} sono il
 * contratto stesso, non un dettaglio opaco dentro una busta sempre-200.
 *
 * <p>{@code ODP-101} ({@code PAA_SINTASSI}) e {@code ODP-109} ({@code PAA_PAGAMENTO_IN_CORSO})
 * non sono cablati: la richiesta ha solo due path param stringa (nessun body da validare) e
 * questa libreria non ha uno stato "pagamento in corso" da segnalare — nessun punto del
 * servizio li lancia oggi.</p>
 */
final class OdpFaultMapper {

	record Esito(String codiceOdp, HttpStatus status) {
	}

	private static final Esito DEFAULT = new Esito("ODP-103", HttpStatus.INTERNAL_SERVER_ERROR);

	private static final Map<FaultPa, Esito> MAPPATURA = Map.of(
			FaultPa.PAA_SEMANTICA, new Esito("ODP-102", HttpStatus.UNPROCESSABLE_ENTITY),
			FaultPa.PAA_SYSTEM_ERROR, DEFAULT,
			FaultPa.PAA_ID_DOMINIO_ERRATO, new Esito("ODP-104", HttpStatus.BAD_REQUEST),
			FaultPa.PAA_ID_INTERMEDIARIO_ERRATO, new Esito("ODP-105", HttpStatus.BAD_REQUEST),
			FaultPa.PAA_STAZIONE_INT_ERRATA, new Esito("ODP-106", HttpStatus.BAD_REQUEST),
			FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, new Esito("ODP-107", HttpStatus.NOT_FOUND),
			FaultPa.PAA_PAGAMENTO_DUPLICATO, new Esito("ODP-108", HttpStatus.CONFLICT),
			FaultPa.PAA_PAGAMENTO_SCADUTO, new Esito("ODP-110", HttpStatus.UNPROCESSABLE_ENTITY),
			FaultPa.PAA_PAGAMENTO_ANNULLATO, new Esito("ODP-111", HttpStatus.UNPROCESSABLE_ENTITY));

	private OdpFaultMapper() {
	}

	/** {@link #DEFAULT} (ODP-103, 500) per qualunque {@link FaultPa} non elencato sopra. */
	static Esito risolvi(FaultPa fault) {
		return MAPPATURA.getOrDefault(fault, DEFAULT);
	}
}
