package it.govpay.pafornode.fault;

/**
 * Eccezione che porta un {@link FaultPa} fino al punto in cui viene tradotta in
 * {@code CtFaultBean} per il DTO di risposta dell'operazione (vedi
 * {@code PaForNodeEndpoint#buildFault}). Porting dell'omonima classe del monolite
 * ({@code it.govpay.core.exceptions.NdpException}), senza il codice di
 * instrumentazione/diagnostica legacy.
 */
public class NdpException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final FaultPa fault;
	private final String descrizione;
	private final String codDominio;

	public NdpException(FaultPa fault, String codDominio) {
		this(fault, null, codDominio, null);
	}

	public NdpException(FaultPa fault, String descrizione, String codDominio) {
		this(fault, descrizione, codDominio, null);
	}

	public NdpException(FaultPa fault, String descrizione, String codDominio, Throwable cause) {
		super(descrizione, cause);
		this.fault = fault;
		this.descrizione = descrizione;
		this.codDominio = codDominio;
	}

	public FaultPa getFault() {
		return fault;
	}

	public String getDescrizione() {
		return descrizione;
	}

	public String getCodDominio() {
		return codDominio;
	}
}
