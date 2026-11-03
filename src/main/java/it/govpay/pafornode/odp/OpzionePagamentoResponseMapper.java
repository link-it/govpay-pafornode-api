package it.govpay.pafornode.odp;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import it.govpay.pafornode.odp.api.model.EnumInstallment;
import it.govpay.pafornode.odp.api.model.EnumPo;
import it.govpay.pafornode.odp.api.model.Installment;
import it.govpay.pafornode.odp.api.model.PaymentOption;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.service.CausaleVersamentoCodec;

/**
 * Mappatura {@code OpzionePagamento}/{@code Pendenza} (v3-nativo, govpay-common-pendenze)
 * verso {@code PaymentOption}/{@code Installment} del contratto REST "pagoPA Payment Options
 * for Organizations" — isolata dal service per testabilita' diretta della tabella di stato
 * (decisa con l'utente, vedi design doc §1).
 *
 * <p><b>Stati raggiungibili solo in parte</b>: {@link GetPaymentOptionsService} restituisce
 * solo le opzioni {@code DISPONIBILE}/{@code ATTIVATA} di una posizione debitoria (le
 * {@code ANNULLATA} sono escluse a monte, confermato anche dai casi d'uso ufficiali pagoPA:
 * "in output deve essere restituita soltanto l'OdP con le installment ancora da saldare") —
 * {@code PO_EXPIRED_NOT_PAYABLE}/{@code POI_EXPIRED_NOT_PAYABLE} (si applicherebbero solo a
 * un'opzione annullata) non sono quindi mai prodotti da questa mappatura.</p>
 *
 * <p>{@code StatoPendenza#PARZIALMENTE_ESEGUITO} &rarr; {@code POI_UNPAID}: deciso con
 * l'utente (2026-11-03) — la spec pagoPA non modella un pagamento parziale di una singola
 * rata (solo {@code PO_PARTIALLY_PAID} a livello di opzione, per un piano rateale con rate
 * miste pagate/non pagate), provvisorio in attesa di un caso reale.</p>
 *
 * <p>Importi in <b>centesimi</b> ({@code amount} e' {@code integer}, non puo' rappresentare i
 * decimali di {@link Pendenza#getImporto()}) — stessa convenzione gia' decisa con l'utente per
 * i metadata pagoPA {@code RC_IMP}/{@code NOTIFICATION_FEE} (vedi
 * {@code it.govpay.pafornode.rpt.ContabilitaResolver}).</p>
 */
final class OpzionePagamentoResponseMapper {

	private static final Set<StatoPendenza> PAGATA = Set.of(StatoPendenza.ESEGUITO, StatoPendenza.ESEGUITO_ALTRO_CANALE,
			StatoPendenza.ESEGUITO_SENZA_RPT, StatoPendenza.INCASSATO);

	private OpzionePagamentoResponseMapper() {
	}

	/** Solo le opzioni {@code DISPONIBILE}/{@code ATTIVATA} della posizione, nell'ordine naturale. */
	static List<PaymentOption> opzioniRestituibili(PosizioneDebitoria posizione, Clock clock) {
		LocalDate oggi = LocalDate.now(clock);
		return posizione.getOpzioniPagamento().stream()
				.filter(opzione -> opzione.getStato() == StatoOpzionePagamento.DISPONIBILE
						|| opzione.getStato() == StatoOpzionePagamento.ATTIVATA)
				.map(opzione -> mappaOpzione(opzione, oggi, clock)).toList();
	}

	private static PaymentOption mappaOpzione(OpzionePagamento opzione, LocalDate oggi, Clock clock) {
		List<Installment> installments = opzione.getPendenze().stream().map(pendenza -> mappaInstallment(pendenza, oggi))
				.toList();
		long importoTotale = installments.stream().mapToLong(Installment::getAmount).sum();
		String descrizione = descrizione(opzione);

		return new PaymentOption().description(descrizione).numberOfInstallments(opzione.getPendenze().size())
				.amount(importoTotale).dueDate(aMezzanotte(opzione.getDataScadenza(), clock))
				.validFrom(aMezzanotte(opzione.getDataInizioValidita(), clock))
				.status(statoOpzione(opzione, oggi)).allCCP(false).installments(installments);
	}

	private static Installment mappaInstallment(Pendenza pendenza, LocalDate oggi) {
		long importo = centesimi(importoConSend(pendenza));
		return new Installment().nav(pendenza.getNumeroAvviso()).iuv(pendenza.getIuv()).amount(importo)
				.description(descrizione(pendenza)).dueDate(pendenza.getDataScadenzaAvviso())
				.validFrom(pendenza.getDataValidita()).status(statoInstallment(pendenza, oggi));
	}

	private static EnumPo statoOpzione(OpzionePagamento opzione, LocalDate oggi) {
		if (opzione.getStato() == StatoOpzionePagamento.DISPONIBILE) {
			boolean scaduta = opzione.getDataScadenza() != null && opzione.getDataScadenza().isBefore(oggi);
			return scaduta ? EnumPo.PO_EXPIRED_UNPAID : EnumPo.PO_UNPAID;
		}
		// ATTIVATA: le altre opzioni sono escluse a monte (vedi opzioniRestituibili).
		boolean tuttePagate = opzione.getPendenze().stream().allMatch(p -> PAGATA.contains(p.getStato()));
		return tuttePagate ? EnumPo.PO_PAID : EnumPo.PO_PARTIALLY_PAID;
	}

	private static EnumInstallment statoInstallment(Pendenza pendenza, LocalDate oggi) {
		StatoPendenza stato = pendenza.getStato();
		if (PAGATA.contains(stato)) {
			return EnumInstallment.POI_PAID;
		}
		if (stato == StatoPendenza.ANNULLATO || stato == StatoPendenza.ANOMALO) {
			return EnumInstallment.POI_INVALID;
		}
		// NON_ESEGUITO o PARZIALMENTE_ESEGUITO (quest'ultimo -> POI_UNPAID, deciso con l'utente).
		boolean scaduta = pendenza.getDataScadenzaAvviso() != null
				&& pendenza.getDataScadenzaAvviso().toLocalDate().isBefore(oggi);
		return scaduta ? EnumInstallment.POI_EXPIRED_UNPAID : EnumInstallment.POI_UNPAID;
	}

	/** Causale decodificata se leggibile, altrimenti la descrizione della posizione — stesso principio di {@code PaVerifyPaymentNoticeService}. */
	private static String descrizione(Pendenza pendenza) {
		String causale = CausaleVersamentoCodec.decode(pendenza.getCausaleVersamento());
		return causale != null ? causale : pendenza.getOpzionePagamento().getPosizioneDebitoria().getDescrizione();
	}

	private static String descrizione(OpzionePagamento opzione) {
		if (opzione.getPendenze().isEmpty()) {
			return opzione.getPosizioneDebitoria().getDescrizione();
		}
		return descrizione(opzione.getPendenze().get(0));
	}

	private static double importoConSend(Pendenza pendenza) {
		return pendenza.getSendImportoTotale() != null ? pendenza.getImporto() + pendenza.getSendImportoTotale()
				: pendenza.getImporto();
	}

	private static long centesimi(double importoInEuro) {
		return Math.round(importoInEuro * 100);
	}

	private static OffsetDateTime aMezzanotte(LocalDate data, Clock clock) {
		return data == null ? null : data.atStartOfDay(clock.getZone()).toOffsetDateTime();
	}
}
