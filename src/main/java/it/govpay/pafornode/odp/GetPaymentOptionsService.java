package it.govpay.pafornode.odp;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pafornode.endpoint.PaForNodeChecks;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.odp.api.model.PaymentOption;
import it.govpay.pafornode.odp.api.model.PaymentOptionsResponse;
import it.govpay.pafornode.rpt.UnitaOperativaResolver;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;

/**
 * Intera operazione {@code getPaymentOptionsByNoticeNumber} del contratto REST "pagoPA Payment
 * Options for Organizations" (issue #4) — sola lettura, nessuna attualizzazione/acquisizione
 * (a differenza di {@code paVerifyPaymentNotice}): il NAV serve solo a individuare la
 * {@code PosizioneDebitoria} (via la sua {@code Pendenza}), non a crearla.
 *
 * <p>Dominio/intermediario/stazione risolti senza campi espliciti nella richiesta (il
 * contratto REST ha solo {@code organization-fiscal-code}/{@code notice-number} nel path, a
 * differenza del SOAP che ha {@code idBrokerPA}/{@code idStation} nel body): si risale dal
 * dominio alla sua stazione di default ({@link DominioEntity#getStazione()}, stesso campo usato
 * da {@link PaForNodeChecks#convertiNumeroAvvisoInIuv}) e da li' all'intermediario
 * ({@link StazioneEntity#getIntermediario()}), poi si verifica che il principal autenticato
 * corrisponda ({@link PaForNodeChecks#verificaPrincipalAutenticato}) — stessa catena di
 * sicurezza condivisa del SOAP, nessuna configurazione nuova.</p>
 *
 * <p>Il NAV richiesto NON deve essere lui stesso pagabile per avere una risposta positiva:
 * serve solo a risalire alla {@code PosizioneDebitoria} (deciso con l'utente, 2026-11-03 —
 * corretto durante l'implementazione da una prima versione che applicava
 * {@link PaForNodeChecks#verificaStatoPagabile} sulla pendenza specifica PRIMA di elencare le
 * sorelle, bloccando con un fault anche quando esisteva gia' un'alternativa pagabile valida).
 * Tutte le opzioni DISPONIBILE/ATTIVATA della posizione sono enumerate da
 * {@link OpzionePagamentoResponseMapper}, a prescindere dallo stato della pendenza/opzione del
 * NAV richiesto. {@code verificaStatoPagabile} entra in gioco solo come fallback, per dare un
 * fault specifico (ANNULLATO/scaduta/duplicata) quando la posizione non ha PROPRIO NESSUNA
 * opzione da offrire.</p>
 */
@Service
public class GetPaymentOptionsService {

	private final DominioRepository dominioRepository;
	private final PendenzaRepository pendenzaRepository;
	private final PagamentoRepository pagamentoRepository;
	private final UnitaOperativaRepository unitaOperativaRepository;
	private final Clock clock;

	public GetPaymentOptionsService(DominioRepository dominioRepository, PendenzaRepository pendenzaRepository,
			PagamentoRepository pagamentoRepository, UnitaOperativaRepository unitaOperativaRepository, Clock clock) {
		this.dominioRepository = dominioRepository;
		this.pendenzaRepository = pendenzaRepository;
		this.pagamentoRepository = pagamentoRepository;
		this.unitaOperativaRepository = unitaOperativaRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PaymentOptionsResponse eseguiGetPaymentOptions(String organizationFiscalCode, String noticeNumber) {
		String codDominio = organizationFiscalCode;

		DominioEntity dominio = dominioRepository.findByCodDominio(codDominio)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_ID_DOMINIO_ERRATO, codDominio));

		StazioneEntity stazione = dominio.getStazione();
		if (stazione == null) {
			throw new NdpException(FaultPa.PAA_STAZIONE_INT_ERRATA, codDominio);
		}
		IntermediarioEntity intermediario = stazione.getIntermediario();
		if (intermediario == null) {
			throw new NdpException(FaultPa.PAA_ID_INTERMEDIARIO_ERRATO, codDominio);
		}
		PaForNodeChecks.verificaPrincipalAutenticato(intermediario, codDominio);

		String iuv = PaForNodeChecks.convertiNumeroAvvisoInIuv(noticeNumber, dominio, codDominio);

		Pendenza pendenza = pendenzaRepository.findByIdDominioAndIuv(dominio.getId(), iuv)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, codDominio));

		PosizioneDebitoria posizione = pendenza.getOpzionePagamento().getPosizioneDebitoria();
		List<PaymentOption> paymentOptions = OpzionePagamentoResponseMapper.opzioniRestituibili(posizione, clock);

		if (paymentOptions.isEmpty()) {
			// La posizione non ha NESSUNA opzione DISPONIBILE/ATTIVATA (es. tutte ANNULLATA, o
			// un'unica opzione gia' esaurita): nulla da restituire. verificaStatoPagabile sulla
			// pendenza del NAV richiesto da' un fault specifico (ANNULLATO/scaduta/duplicata);
			// se anche quella passasse (non dovrebbe succedere per costruzione con la lista
			// vuota), fallback generico.
			PaForNodeChecks.verificaStatoPagabile(pendenza, codDominio, clock, pagamentoRepository);
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, codDominio);
		}

		return new PaymentOptionsResponse().organizationFiscalCode(dominio.getCodDominio())
				.companyName(dominio.getRagioneSociale())
				.officeName(UnitaOperativaResolver.risolviNome(unitaOperativaRepository, posizione)).standin(false)
				.paymentOptions(paymentOptions);
	}
}
