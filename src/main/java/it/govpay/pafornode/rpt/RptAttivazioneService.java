package it.govpay.pafornode.rpt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.xml.transform.stream.StreamSource;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.ObjectFactory;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Response;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RptRepository;

/**
 * Attivazione di una RPT (analisi completa del legacy, {@code CtPaymentPABuilder}/
 * {@code it.govpay.core.business.Rpt#annullaRPTPendenti}, nel design doc): annulla le RPT
 * pendenti sulla stessa coppia dominio/iuv, poi persiste la nuova RPT con l'XML della
 * risposta serializzato — {@link #attiva}/{@link #deserializzaDati} per {@code paGetPayment}
 * (V1, {@code CtPaymentPA}), {@link #attivaV2}/{@link #deserializzaDatiV2} per {@code
 * paGetPaymentV2} ({@code CtPaymentPAV2}, versione RPT {@code SANP_321_V2}, {@code
 * codCarrello}).
 *
 * <p>{@link #ricostruisci} copre invece il caso "standin" (punto aperto #14 del design
 * doc): una RPT mai attivata da GovPay, ricostruita al volo quando arriva direttamente la
 * ricevuta — porting di {@code CtReceiptUtils.ricostruisciRPT}/{@code
 * CtReceiptV2Utils.ricostruisciRPT} del monolite, usato da {@code PaSendRTService}.</p>
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
	private final ObjectFactory objectFactory = new ObjectFactory();
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
	 * Annulla le RPT pendenti sulla stessa coppia dominio/iuv e persiste la nuova RPT
	 * attivata (stato finale {@code RPT_ACCETTATA_NODO}, come nel legacy), con l'XML della
	 * risposta {@code paGetPayment} come corpo.
	 *
	 * <p>Acquisisce prima il lock sulla pendenza stessa ({@code SELECT ... FOR UPDATE}):
	 * il lock sulle sole RPT pendenti (in {@link #annullaPendenti}) non basta a serializzare
	 * due attivazioni concorrenti quando non ce n'e' ancora nessuna — entrambe troverebbero
	 * un risultato vuoto e creerebbero due RPT attive sulla stessa pendenza.</p>
	 */
	@Transactional
	public Rpt attiva(Pendenza pendenza, DominioEntity dominio, StazioneEntity stazione, String ccp,
			CtPaymentPA ctPayment) {
		Rpt rpt = nuovaRptAttivata(pendenza, dominio, stazione, ccp, VERSIONE_SANP_240, null);
		rpt.setXmlRpt(serializza(ctPayment));
		return rptRepository.save(rpt);
	}

	/**
	 * Come {@link #attiva}, per {@code paGetPaymentV2}: versione RPT {@code SANP_321_V2},
	 * payload {@code CtPaymentPAV2} (puo' contenere Marca da Bollo Telematica/metadati,
	 * assenti da {@code CtPaymentPA}), e {@code codCarrello} dalla richiesta (gia' troncato
	 * a 35 caratteri dal chiamante, stesso limite del legacy).
	 */
	@Transactional
	public Rpt attivaV2(Pendenza pendenza, DominioEntity dominio, StazioneEntity stazione, String ccp,
			String codCarrello, CtPaymentPAV2 ctPayment) {
		Rpt rpt = nuovaRptAttivata(pendenza, dominio, stazione, ccp, VERSIONE_SANP_321_V2, codCarrello);
		rpt.setXmlRpt(serializzaV2(ctPayment));
		return rptRepository.save(rpt);
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
	 * Ricostruisce una RPT "standin": nessuna richiesta di pagamento originale da cui
	 * ripartire, {@code xmlRpt} resta {@code null} — la validazione semantica
	 * RT-contro-RPT viene quindi saltata dal chiamante, stesso comportamento del legacy
	 * che la condiziona a {@code rpt.getXmlRpt() != null}.
	 *
	 * <p>A differenza di {@link #attiva}/{@link #attivaV2}: nessun {@link
	 * #annullaPendenti} (per costruzione non esiste ancora nessuna RPT su questa
	 * pendenza, altrimenti il chiamante non avrebbe scelto questo percorso); la stazione
	 * e' quella di default del dominio ({@link DominioEntity#getStazione()}), non quella
	 * autenticata dalla chiamata corrente — non c'e' stata nessuna attivazione a cui
	 * agganciarsi; {@code codMsgRichiesta}/{@code iur} sono il {@code receiptId} della
	 * ricevuta in arrivo, non un valore generato qui.</p>
	 */
	@Transactional
	public Rpt ricostruisci(Pendenza pendenza, DominioEntity dominio, String versione, String receiptId,
			OffsetDateTime dataPagamento) {
		pendenzaRepository.findByIdForUpdate(pendenza.getId())
				.orElseThrow(() -> new IllegalStateException("Pendenza [" + pendenza.getId() + "] non trovata."));
		StazioneEntity stazione = dominio.getStazione();
		if (stazione == null) {
			throw new IllegalStateException("Dominio [" + dominio.getCodDominio() + "] senza stazione di default.");
		}

		OffsetDateTime adesso = OffsetDateTime.now(clock);
		Rpt rpt = new Rpt();
		rpt.setIdVersamento(pendenza.getId());
		rpt.setIuv(pendenza.getIuv());
		rpt.setIur(receiptId);
		rpt.setCodDominio(dominio.getCodDominio());
		rpt.setCodStazione(stazione.getCodStazione());
		rpt.setCodMsgRichiesta(receiptId);
		rpt.setVersione(versione);
		rpt.setStato(StatoRpt.RPT_ACCETTATA_NODO.name());
		rpt.setDataMsgRichiesta(dataPagamento != null ? dataPagamento : adesso);
		rpt.setDataAggiornamentoStato(adesso);
		rpt.setModelloPagamento(MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP);
		return rptRepository.save(rpt);
	}

	private Rpt nuovaRptAttivata(Pendenza pendenza, DominioEntity dominio, StazioneEntity stazione, String ccp,
			String versione, String codCarrello) {
		pendenzaRepository.findByIdForUpdate(pendenza.getId())
				.orElseThrow(() -> new IllegalStateException("Pendenza [" + pendenza.getId() + "] non trovata."));

		annullaPendenti(dominio.getCodDominio(), pendenza.getIuv());

		OffsetDateTime adesso = OffsetDateTime.now(clock);
		Rpt rpt = new Rpt();
		rpt.setIdVersamento(pendenza.getId());
		rpt.setIuv(pendenza.getIuv());
		rpt.setIur(ccp);
		rpt.setCodDominio(dominio.getCodDominio());
		rpt.setCodStazione(stazione.getCodStazione());
		rpt.setCodMsgRichiesta(UUID.randomUUID().toString().replace("-", ""));
		rpt.setVersione(versione);
		rpt.setCodCarrello(codCarrello);
		// Stato finale di una attivazione riuscita, non l'intermedio RPT_ATTIVATA: nel legacy
		// la RPT passa a RPT_ACCETTATA_NODO prima del commit (qui non c'e' un invio asincrono
		// separato da attendere, l'intera operazione e' nella stessa transazione).
		rpt.setStato(StatoRpt.RPT_ACCETTATA_NODO.name());
		rpt.setDataMsgRichiesta(adesso);
		rpt.setDataAggiornamentoStato(adesso);
		rpt.setModelloPagamento(MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP);
		return rpt;
	}

	/**
	 * Rilegge il {@code CtPaymentPA} dall'XML appena persistito su {@link Rpt#getXmlRpt()},
	 * invece di riusare l'oggetto in memoria passato ad {@link #attiva} — stessa garanzia di
	 * coerenza-con-quanto-scritto del legacy ({@code JaxbUtils.toPaGetPaymentResRPT}).
	 */
	public CtPaymentPA deserializzaDati(Rpt rpt) {
		try {
			Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
			// Le classi generate da paForNode.xsd non hanno @XmlRootElement (il root e'
			// dichiarato nello schema solo come <element>, non <complexType> con binding
			// diretto, stesso motivo di Jaxb2Marshaller#setCheckForXmlRootElement in
			// WsConfig): la forma a 1 argomento di unmarshal() non riconoscerebbe l'elemento
			// come un root valido ("elementi previsti sono (none)"). Specificare la classe
			// attesa fa risolvere l'elemento tramite l'@XmlElementDecl di ObjectFactory.
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

	private void annullaPendenti(String codDominio, String iuv) {
		List<Rpt> pendenti = rptRepository.findByCodDominioAndIuvAndStatoInAndModelloPagamento(codDominio, iuv,
				StatoRpt.STATI_PENDENTI, MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP);
		if (pendenti.isEmpty()) {
			return;
		}
		OffsetDateTime adesso = OffsetDateTime.now(clock);
		for (Rpt rpt : pendenti) {
			rpt.setStato(StatoRpt.RPT_ANNULLATA.name());
			rpt.setDescrizioneStato("Ricevuta richiesta di un nuovo tentativo di pagamento");
			rpt.setDataAggiornamentoStato(adesso);
		}
		rptRepository.saveAll(pendenti);
	}

	private byte[] serializza(CtPaymentPA ctPayment) {
		PaGetPaymentRes risposta = new PaGetPaymentRes();
		risposta.setOutcome(StOutcome.OK);
		risposta.setData(ctPayment);
		try {
			Marshaller marshaller = jaxbContext.createMarshaller();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			marshaller.marshal(objectFactory.createPaGetPaymentRes(risposta), output);
			return output.toByteArray();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}

	private byte[] serializzaV2(CtPaymentPAV2 ctPayment) {
		PaGetPaymentV2Response risposta = new PaGetPaymentV2Response();
		risposta.setOutcome(StOutcome.OK);
		risposta.setData(ctPayment);
		try {
			Marshaller marshaller = jaxbContextV2.createMarshaller();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			marshaller.marshal(objectFactory.createPaGetPaymentV2Response(risposta), output);
			return output.toByteArray();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}
}
