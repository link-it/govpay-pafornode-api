package it.govpay.pafornode.endpoint;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.utils.IuvUtils;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
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
		OffsetDateTime scadenzaAvviso = pendenza.getDataScadenzaAvviso();
		if (scadenzaAvviso != null && scadenzaAvviso.isBefore(OffsetDateTime.now(clock))) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCADUTO, codDominio);
		}

		OpzionePagamento opzione = pendenza.getOpzionePagamento();
		LocalDate oggi = LocalDate.now(clock);
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
