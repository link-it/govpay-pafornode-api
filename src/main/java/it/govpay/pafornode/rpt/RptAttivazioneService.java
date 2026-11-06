package it.govpay.pafornode.rpt;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.OffsetDateTime;

import javax.xml.transform.stream.StreamSource;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Response;
import it.govpay.common.entity.DominioEntity;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RptRepository;

/**
 * Nascita/aggiornamento di una RPT dopo questa fase dell'issue #6: {@link #ricostruisci} e'
 * ora l'UNICO modo in cui questo servizio crea una riga {@code rpt} — l'attivazione
 * ({@code paGetPayment}/{@code paGetPaymentV2}) e' diventata una sola lettura della pendenza,
 * nessuna RPT viene piu' scritta li' (vedi Javadoc di classe di
 * {@code PaGetPaymentActivationService}). La riga nasce quindi solo all'arrivo della
 * ricevuta, come nel percorso StandIn del core (porting di {@code
 * CtReceiptUtils.ricostruisciRPT}/{@code CtReceiptV2Utils.ricostruisciRPT} del monolite),
 * ma e' ora il percorso UNICO, orchestrato da {@code PaSendRTService} con lock sulla
 * pendenza e gestione dei duplicati.
 *
 * <p>{@link #deserializzaDati}/{@link #deserializzaDatiV2} e
 * {@link #rifiutaPerValidazioneSemantica} restano necessari per le RPT scritte dal
 * <b>vecchio core prima del passaggio alla 3.11</b> (hanno {@code xmlRpt}: la loro ricevuta,
 * se arriva dopo il passaggio, va aggiornata — non duplicata — con la validazione semantica
 * RT-contro-RPT ancora applicabile; vedi "Casi da non dimenticare" dell'issue). Per le RPT
 * nate da {@link #ricostruisci} {@code xmlRpt} e' sempre {@code null}: quella validazione
 * viene saltata dal chiamante, stessa condizione del legacy.</p>
 */
@Service
public class RptAttivazioneService {

	/** Codifica legacy di {@code ModelloPagamento.ATTIVATO_PRESSO_PSP}, unico modello gestito. */
	public static final String MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP = "4";

	/** Versione RPT per {@code paSendRT} (V1) e per la corrispondente ricostruzione "standin". */
	public static final String VERSIONE_SANP_240 = "SANP_240";
	/** Versione RPT per {@code paSendRTV2} (V2) e per la corrispondente ricostruzione "standin". */
	public static final String VERSIONE_SANP_321_V2 = "SANP_321_V2";
	/**
	 * Marcatura di una RPT attivata con {@code paGetPaymentV2} (quindi {@link
	 * #VERSIONE_SANP_321_V2}) la cui ricevuta e' arrivata tramite {@code paSendRT} (V1) —
	 * porting di {@code VersioneRPT.RPTV2_RTV1} del monolite. Scritta da {@code
	 * PaSendRTService#eseguiSendRT} al posto della versione originale quando rileva il
	 * mismatch, persiste sulla RPT anche in caso di successivo rifiuto/retry.
	 */
	public static final String VERSIONE_RPTV2_RTV1 = "RPTV2_RTV1";
	/**
	 * Come {@link #VERSIONE_RPTV2_RTV1}, caso simmetrico: RPT attivata con {@code
	 * paGetPayment} ({@link #VERSIONE_SANP_240}), ricevuta arrivata tramite {@code
	 * paSendRTV2} — porting di {@code VersioneRPT.RPTV1_RTV2}.
	 */
	public static final String VERSIONE_RPTV1_RTV2 = "RPTV1_RTV2";

	private final PendenzaRepository pendenzaRepository;
	private final RptRepository rptRepository;
	private final Clock clock;
	private final JAXBContext jaxbContext;
	private final JAXBContext jaxbContextV2;

	public RptAttivazioneService(PendenzaRepository pendenzaRepository, RptRepository rptRepository, Clock clock) {
		this.pendenzaRepository = pendenzaRepository;
		this.rptRepository = rptRepository;
		this.clock = clock;
		try {
			this.jaxbContext = JAXBContext.newInstance(PaGetPaymentRes.class);
			this.jaxbContextV2 = JAXBContext.newInstance(PaGetPaymentV2Response.class);
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Persiste il rifiuto della validazione semantica RT-contro-RPT ({@code
	 * RT_RIFIUTATA_PA}, con il corpo grezzo della ricevuta rifiutata e il messaggio del
	 * controllo fallito) — stesso comportamento del legacy ({@code
	 * CtReceiptUtils.acquisisciRT}: la RPT rifiutata viene comunque aggiornata prima di
	 * rilanciare {@code PAA_SEMANTICA}).
	 *
	 * <p><b>Nessuna transazione propria (volutamente)</b>: {@code rpt} e' gia' gestito
	 * dalla transazione del chiamante, che la detiene con lock pessimistico — una
	 * {@code REQUIRES_NEW} qui aprirebbe una seconda transazione/connessione che si
	 * bloccherebbe in attesa dello stesso lock, mai rilasciato finche' il chiamante non
	 * ritorna (deadlock). Partecipa quindi alla stessa transazione del chiamante, che DEVE
	 * marcare {@code PAA_SEMANTICA} con {@code noRollbackFor} (vedi {@code
	 * PaSendRTService#eseguiSendRT}/{@code V2}) perche' questa scrittura sopravviva al
	 * fault che la segue.</p>
	 *
	 * @param rpt       la RPT da aggiornare, gia' gestita dalla transazione del chiamante
	 * @param xmlRt     corpo grezzo della ricevuta rifiutata, da conservare per diagnosi
	 * @param messaggio descrizione del controllo semantico fallito
	 */
	@Transactional
	public void rifiutaPerValidazioneSemantica(Rpt rpt, byte[] xmlRt, String messaggio) {
		rpt.setStato(StatoRpt.RT_RIFIUTATA_PA.name());
		rpt.setDescrizioneStato(messaggio);
		rpt.setXmlRt(xmlRt);
		rpt.setDataAggiornamentoStato(OffsetDateTime.now(clock));
		rptRepository.save(rpt);
	}

	/**
	 * Ricostruisce una RPT: nessuna richiesta di pagamento originale da cui ripartire,
	 * {@code xmlRpt} resta {@code null} — la validazione semantica RT-contro-RPT viene
	 * quindi saltata dal chiamante, stesso comportamento del legacy che la condiziona a
	 * {@code rpt.getXmlRpt() != null}. Nessun annullamento di RPT pendenti: per costruzione
	 * non esiste ancora nessuna RPT su questa pendenza, altrimenti il chiamante non avrebbe
	 * scelto questo percorso. {@code codMsgRichiesta}/{@code iur} sono il {@code receiptId}
	 * della ricevuta in arrivo, non un valore generato qui.
	 *
	 * <p>{@code codStazione} e' quella AUTENTICATA della richiesta corrente ({@code
	 * codStazioneRichiesta}), non la stazione di default del dominio (bug trovato in
	 * revisione, 2026-11-04: usava {@code DominioEntity#getStazione()}, inconsistente con
	 * {@link #creaOrfana} e con l'issue #6, che ammette esplicitamente stazioni diverse da
	 * quella di default anche con pendenza nota — "Stazioni accettate in paSendRT/
	 * paSendRTV2: solo quelle configurate in GovPay (anche diverse dalla stazione del
	 * dominio)"). Il chiamante l'ha gia' validata ({@code PaSendRTService
	 * #verificaAutorizzazione}), nessun controllo di nullita' necessario qui.</p>
	 */
	@Transactional
	public Rpt ricostruisci(Pendenza pendenza, DominioEntity dominio, String codStazioneRichiesta, String versione,
			String receiptId, OffsetDateTime dataPagamento) {
		pendenzaRepository.findByIdForUpdate(pendenza.getId())
				.orElseThrow(() -> new IllegalStateException("Pendenza [" + pendenza.getId() + "] non trovata."));

		OffsetDateTime adesso = OffsetDateTime.now(clock);
		Rpt rpt = new Rpt();
		rpt.setIdVersamento(pendenza.getId());
		rpt.setIuv(pendenza.getIuv());
		rpt.setIur(receiptId);
		rpt.setCodDominio(dominio.getCodDominio());
		rpt.setCodStazione(codStazioneRichiesta);
		rpt.setCodMsgRichiesta(receiptId);
		rpt.setVersione(versione);
		rpt.setStato(StatoRpt.RPT_ACCETTATA_NODO.name());
		rpt.setDataMsgRichiesta(dataPagamento != null ? dataPagamento : adesso);
		rpt.setDataAggiornamentoStato(adesso);
		rpt.setModelloPagamento(MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP);
		return rptRepository.save(rpt);
	}

	/**
	 * Come {@link #ricostruisci}, per una ricevuta SENZA pendenza (issue #6, stazione in
	 * broadcast): nessuna {@link Pendenza} da bloccare/riferire ({@code idVersamento} resta
	 * {@code null}), {@code idApplicazione} risolta dal dominio dal chiamante ({@code
	 * PaSendRTService}, via {@code ApplicazioneDominioResolver} — {@code null} se non
	 * risolvibile, non e' un fault). {@code codStazione} e' quella AUTENTICATA della
	 * richiesta corrente, non quella di default del dominio come in {@link #ricostruisci}:
	 * non c'e' nessuna pendenza/dominio a cui era gia' agganciata una stazione, e in
	 * broadcast la ricevuta puo' arrivare legittimamente da una stazione diversa da quella
	 * di default. Nessun {@code modelloPagamento}: non si applica nessun modello di
	 * pagamento, non essendoci stata nessuna attivazione di alcun tipo.
	 *
	 * <p>{@code GenerationType.IDENTITY} su {@link Rpt#getId()}: {@code rptRepository.save}
	 * esegue l'insert immediatamente, non al commit — una violazione del vincolo unique
	 * {@code (iuv, ccp, cod_dominio)} (due ricevute concorrenti con lo stesso {@code
	 * receiptId} su questo stesso IUV sconosciuto) emerge quindi qui, non a un commit
	 * successivo. Il chiamante NON la cattura (bug di revisione corretto, 2026-11-04: questo
	 * metodo e' {@code @Transactional} e partecipa alla transazione del chiamante — un catch
	 * li' arriverebbe DOPO che il proxy Spring ha gia' marcato quella transazione
	 * rollback-only, causando {@code UnexpectedRollbackException} al commit invece del
	 * fault voluto): si lascia propagare, rollback normale, tradotta in {@code
	 * PAA_RECEIPT_DUPLICATA} da {@code PaForNodeEndpoint}, fuori da qualunque transazione.</p>
	 */
	@Transactional
	public Rpt creaOrfana(String codDominio, String iuv, String codStazione, Long idApplicazione, String versione,
			String receiptId, OffsetDateTime dataPagamento) {
		OffsetDateTime adesso = OffsetDateTime.now(clock);
		Rpt rpt = new Rpt();
		rpt.setIdApplicazione(idApplicazione);
		rpt.setIuv(iuv);
		rpt.setIur(receiptId);
		rpt.setCodDominio(codDominio);
		rpt.setCodStazione(codStazione);
		rpt.setCodMsgRichiesta(receiptId);
		rpt.setVersione(versione);
		rpt.setStato(StatoRpt.RPT_ACCETTATA_NODO.name());
		rpt.setDataMsgRichiesta(dataPagamento != null ? dataPagamento : adesso);
		rpt.setDataAggiornamentoStato(adesso);
		return rptRepository.save(rpt);
	}

	/**
	 * Rilegge il {@code CtPaymentPA} dall'XML conservato su {@link Rpt#getXmlRpt()} — una RPT
	 * scritta dal vecchio core prima del passaggio alla 3.11 (vedi Javadoc di classe).
	 */
	public CtPaymentPA deserializzaDati(Rpt rpt) {
		try {
			Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
			// Con la classe attesa l'unmarshal restituisce il tipo giusto senza cast.
			JAXBElement<PaGetPaymentRes> risultato = unmarshaller
					.unmarshal(new StreamSource(new ByteArrayInputStream(rpt.getXmlRpt())), PaGetPaymentRes.class);
			return risultato.getValue().getData();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Come {@link #deserializzaDati}, per {@code paGetPaymentV2}. */
	public CtPaymentPAV2 deserializzaDatiV2(Rpt rpt) {
		try {
			Unmarshaller unmarshaller = jaxbContextV2.createUnmarshaller();
			JAXBElement<PaGetPaymentV2Response> risultato = unmarshaller.unmarshal(
					new StreamSource(new ByteArrayInputStream(rpt.getXmlRpt())), PaGetPaymentV2Response.class);
			return risultato.getValue().getData();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}
}
