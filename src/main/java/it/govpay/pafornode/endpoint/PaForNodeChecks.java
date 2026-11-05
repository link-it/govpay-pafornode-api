package it.govpay.pafornode.endpoint;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.HttpServletRequest;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.utils.IuvUtils;
import it.govpay.common.auth.AuthTypeAccessor;
import it.govpay.common.auth.spi.AuthType;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.gde.SoapTransportUtils;
import it.govpay.pafornode.security.PaForNodePrincipalLoader;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.repository.PagamentoRepository;

/**
 * Controlli di business comuni a {@code paVerifyPaymentNotice} e {@code paGetPayment}
 * (metodi statici, nessuno stato proprio): estratti per riuso quando {@code paGetPayment} e'
 * stato spostato in un servizio transazionale separato (vedi
 * {@code it.govpay.pafornode.rpt.PaGetPaymentActivationService}).
 */
public final class PaForNodeChecks {

	private static final DateTimeFormatter DATA_PAGAMENTO_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private PaForNodeChecks() {
	}

	/**
	 * L'identita' che ha autenticato la chiamata (certificato client o equivalente, secondo
	 * il metodo {@code govpay.auth.*} abilitato) deve corrispondere all'intermediario
	 * dichiarato in {@code idBrokerPA} — altrimenti un intermediario autenticato potrebbe
	 * dichiararsi un intermediario diverso dal proprio. Stesso controllo del legacy
	 * ({@code AuthorizationManager.checkPrincipal}, eseguito quando {@code isPddAuthEnable()});
	 * qui e' sempre attivo invece che opzionale, perche' a differenza del legacy
	 * l'autenticazione non e' opzionale in questa generazione: se si arriva a questo metodo la
	 * catena di sicurezza di govpay-common-auth ha gia' autenticato qualcuno (nega l'accesso
	 * prima, altrimenti) — un {@code Authentication} assente e' quindi trattato come
	 * fallimento dell'autorizzazione, non come "controllo disabilitato".
	 */
	public static void verificaPrincipalAutenticato(IntermediarioEntity intermediario, String codDominio) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			// Esplicito, non delegato al confronto sotto: Objects.equals(null, null) supererebbe
			// il controllo se anche l'intermediario non avesse un principal configurato — un
			// esito corretto per caso, non per costruzione. Nessuna richiesta dovrebbe comunque
			// arrivare qui senza autenticazione (la catena di sicurezza la blocca prima), ma il
			// metodo deve rifiutare esplicitamente, non "passare" per coincidenza.
			throw new NdpException(FaultPa.PAA_SYSTEM_ERROR, "Autorizzazione fallita: principal non fornito.",
					codDominio);
		}
		String principalAutenticato = authentication.getName();
		if (!Objects.equals(intermediario.getPrincipal(), principalAutenticato)) {
			throw new NdpException(FaultPa.PAA_SYSTEM_ERROR,
					"Autorizzazione fallita: principal fornito (" + principalAutenticato
							+ ") non valido per l'intermediario (" + intermediario.getCodIntermediario() + ").",
					codDominio);
		}
	}

	/**
	 * Issue #10, finding #2 di revisione (2026-11-05): {@code paVerifyPaymentNotice}/{@code
	 * paGetPayment}/{@code paGetPaymentV2} non sono previste sul canale interno — SOLO {@code
	 * paSendRT}/{@code paSendRTV2} lo sono ({@code PaSendRTService#verificaCanale} gestisce
	 * la propria autorizzazione da solo, diversa perche' quel canale ammette entrambi i tipi
	 * di autenticazione). Qui si rifiuta SEMPRE una chiamata arrivata sul path interno,
	 * qualunque sia l'identita' autenticata: prima queste operazioni verificavano solo il
	 * confronto principal/intermediario (sempre superabile da un intermediario autenticato
	 * per davvero, su qualunque path, dato che Spring-WS dispatcha per payload-root e non per
	 * path) — un intermediario autenticato sul canale interno poteva quindi eseguire
	 * {@code paGetPayment} li', bypassando di fatto la restrizione di canale che l'issue
	 * prevede solo per l'invio delle ricevute.
	 *
	 * <p>Bug di revisione corretto 2026-11-05: rifiutava solo in base al PATH (canale
	 * interno), non anche in base al METODO di autenticazione usato sul path Nodo — un'
	 * utenza applicativa (autenticata con {@code AuthType.BASIC}/{@code LDAP}) il cui
	 * {@code principal} coincidesse (anche per puro caso) con quello di un intermediario
	 * registrato superava comunque il successivo confronto principal/intermediario e poteva
	 * eseguire l'operazione su {@code /ws} con credenziali del canale interno. Ora si
	 * rifiuta anche quando {@link PaForNodePrincipalLoader#autenticaUtenzaApplicativa} e'
	 * vero, indipendentemente dal path: queste tre operazioni devono SEMPRE essere
	 * autenticate con un metodo da intermediario (SSL/SSL_HEADER/HEADER), mai da utenza
	 * applicativa.
	 */
	public static void verificaCanaleNodo(String codDominio) {
		HttpServletRequest request = SoapTransportUtils.currentHttpServletRequest();
		if (SoapTransportUtils.isPathCanaleInterno(request)) {
			throw new NdpException(FaultPa.PAA_SYSTEM_ERROR,
					"Operazione non disponibile sul canale interno.", codDominio);
		}
		AuthType authType = request != null ? AuthTypeAccessor.current(request) : null;
		if (PaForNodePrincipalLoader.autenticaUtenzaApplicativa(authType)) {
			throw new NdpException(FaultPa.PAA_SYSTEM_ERROR,
					"Autorizzazione fallita: metodo di autenticazione non ammesso per questa operazione.",
					codDominio);
		}
	}

	/**
	 * {@code numeroAvviso} si converte in IUV solo conoscendo l'AuxDigit configurato sul
	 * dominio (diversamente dal legacy, che derivava lo IUV per formato prima ancora di
	 * risolvere il dominio — qui il dominio va risolto prima, §3ter del design doc).
	 */
	public static String convertiNumeroAvvisoInIuv(String numeroAvviso, DominioEntity dominio, String codDominio) {
		Integer applicationCode = dominio.getStazione() == null ? null : dominio.getStazione().getApplicationCode();
		try {
			return IuvUtils.convertiDaNumeroAvviso(numeroAvviso, dominio.getAuxDigit(), dominio.getSegregationCode(),
					applicationCode);
		} catch (IllegalArgumentException e) {
			throw new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e);
		}
	}

	/**
	 * {@code ANNULLATO}/{@code ESEGUITO}/{@code ESEGUITO_ALTRO_CANALE}/scadenza pendenza o
	 * opzione superata → fault. Non tenta l'attualizzazione per validità scaduta
	 * (richiederebbe l'acquisizione live, non ancora implementata): una pendenza con validità
	 * decorsa ma non scaduta viene comunque restituita con i dati correnti.
	 */
	public static void verificaStatoPagabile(Pendenza pendenza, String codDominio, Clock clock,
			PagamentoRepository pagamentoRepository) {
		if (pendenza.getStato() == StatoPendenza.ANNULLATO) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_ANNULLATO, codDominio);
		}
		if (pendenza.getStato() == StatoPendenza.ESEGUITO || pendenza.getStato() == StatoPendenza.ESEGUITO_ALTRO_CANALE) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_DUPLICATO,
					descrizioneDuplicato(pendenza, codDominio, pagamentoRepository), codDominio);
		}
		LocalDate oggi = LocalDate.now(clock);
		// Il mapper scrive sempre questa data a mezzanotte (un giorno puro, nessun istante):
		// confronto per GIORNO, non per istante, altrimenti una scadenza odierna verrebbe
		// rifiutata non appena l'orologio supera la mezzanotte dello stesso giorno — il
		// legacy mantiene valido l'intero giorno di scadenza.
		OffsetDateTime scadenzaAvviso = pendenza.getDataScadenzaAvviso();
		if (scadenzaAvviso != null && scadenzaAvviso.toLocalDate().isBefore(oggi)) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCADUTO, codDominio);
		}

		OpzionePagamento opzione = pendenza.getOpzionePagamento();
		if (opzione.getDataInizioValidita() != null && opzione.getDataInizioValidita().isAfter(oggi)) {
			// Nessun FaultPa legacy corrisponde a "non ancora aperta" (concetto introdotto in
			// questa generazione dall'opzione di pagamento v3): stessa famiglia della pendenza
			// non ancora nota, per coerenza.
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, codDominio);
		}
		if (opzione.getDataScadenza() != null && opzione.getDataScadenza().isBefore(oggi)) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCADUTO, codDominio);
		}
	}

	/** Dettaglio IUR/data pagamento se disponibile, altrimenti nessuna descrizione aggiuntiva. */
	private static String descrizioneDuplicato(Pendenza pendenza, String codDominio,
			PagamentoRepository pagamentoRepository) {
		List<Pagamento> pagamenti = pagamentoRepository.findByCodDominioAndIuv(codDominio, pendenza.getIuv());
		if (pagamenti.isEmpty()) {
			return null;
		}
		Pagamento pagamento = pagamenti.get(0);
		String data = pagamento.getDataPagamento() == null ? ""
				: DATA_PAGAMENTO_FORMATTER.format(pagamento.getDataPagamento());
		return "Il pagamento risulta già effettuato in data " + data + " [Iur:" + pagamento.getIur() + "]";
	}
}
