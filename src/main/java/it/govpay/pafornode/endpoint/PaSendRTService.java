package it.govpay.pafornode.endpoint;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.xml.datatype.XMLGregorianCalendar;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceipt;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.ObjectFactory;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Request;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Response;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.rpt.RptAttivazioneService;
import it.govpay.pafornode.rpt.StatoRpt;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pafornode.ricevuta.AcquisizioneRicevutaService;
import it.govpay.pafornode.ricevuta.DatiRicevutaPagamento;
import it.govpay.pafornode.ricevuta.DatiRicevutaPagamento.DatiTrasferimentoRicevuto;

/**
 * Intera operazione {@code paSendRT}/{@code paSendRTV2}: acquisizione di una ricevuta di
 * pagamento (RT) su una RPT gia' attivata — porting del percorso principale di
 * {@code CtReceiptUtils.acquisisciRT}/{@code paSendRT(V2)} del monolite ({@code
 * jars/core/.../utils/CtReceiptUtils.java}, {@code wars/api-pagopa/.../
 * PagamentiTelematiciCCPImpl.java} righe 260-424/1142-1282), §3octies del design doc.
 *
 * <p>A differenza di {@code paGetPayment}/{@code paVerifyPaymentNotice}, qui IUV e
 * dominio arrivano direttamente dalla ricevuta ({@code CtReceipt.getCreditorReferenceId()}/
 * {@code getFiscalCode()}), non da un {@code CtQrCode} con numero avviso da convertire —
 * nessun {@code PaForNodeChecks.convertiNumeroAvvisoInIuv} qui.</p>
 *
 * <p><b>Logica di scrittura sull'aggregato pendenza/RPT/pagamento</b> delegata a
 * {@link AcquisizioneRicevutaService}: questo servizio si
 * occupa solo di cio' che e' specifico del contratto paForNode — controlli di
 * autorizzazione, ricerca/lock della RPT, deserializzazione del payload originale
 * ({@link RptAttivazioneService#deserializzaDati}/{@code V2}, serve il suo
 * {@code Jaxb2Marshaller}) e la validazione semantica RT-contro-RPT (qui, non in
 * govpay-common-pendenze: dipende dai tipi JAXB della richiesta originale).</p>
 *
 * <p><b>Ricostruzione "standin"</b> (punto aperto #14 del design doc, {@code
 * CtReceiptUtils.ricostruisciRPT}/{@code CtReceiptV2Utils.ricostruisciRPT} nel monolite):
 * se non esiste nessuna RPT in attesa di ricevuta ({@code RPT_ACCETTATA_NODO}) E non
 * esiste nessun'altra RPT per questa pendenza, significa che il pagamento e' stato
 * attivato direttamente al PSP tramite il Nodo, senza mai passare da {@code
 * paGetPayment}/{@code paGetPaymentV2} — {@link #trovaRptInAttesa} ricostruisce in quel
 * caso una RPT minimale ({@link RptAttivazioneService#ricostruisci}, {@code xmlRpt}
 * sempre {@code null}). Il filtro per modello su questa ricerca e' ASIMMETRICO fra le due
 * operazioni, fedele al legacy: {@code paSendRT} (V1) filtra per {@code
 * ATTIVATO_PRESSO_PSP} (una RPT di un altro modello non blocca lo standin), {@code
 * paSendRTV2} non filtra affatto (qualunque RPT, di qualunque modello, blocca lo standin)
 * — vedi Javadoc di {@link #trovaRptInAttesa}. Se esiste gia' una RPT che soddisfa il
 * criterio, resta {@code PAA_RPT_SCONOSCIUTA} come nel legacy: non si ricostruisce una
 * seconda volta.</p>
 *
 * <p>La deserializzazione/validazione semantica RT-contro-RPT ({@link
 * #validaSemantica}/{@code V2}) viene saltata quando la RPT non ha {@code xmlRpt} (il caso
 * standin: nessuna richiesta originale con cui confrontarsi) — stessa condizione del
 * legacy.</p>
 *
 * <p><b>Casi ibridi</b> (punti #17/#18 del design doc, {@code CtReceiptUtils}/{@code
 * CtReceiptV2Utils} del monolite): se la versione della RPT trovata non e' quella attesa
 * dall'operazione chiamata, la ricevuta e' arrivata tramite l'operazione "sbagliata" —
 * confermato scenario reale da pagoPA, non solo teorico. {@link #eseguiSendRT} marca in
 * quel caso la RPT con {@code VERSIONE_RPTV2_RTV1} e valida con {@link
 * #validaSemanticaIbridaV2RptV1Rt}; {@link #eseguiSendRTV2}, simmetricamente, con {@code
 * VERSIONE_RPTV1_RTV2} e {@link #validaSemanticaIbridaV1RptV2Rt}. Il legacy, sul lato V2,
 * ha un TERZO ramo per una RPT pre-paForNode (SANP_230) — non riportato: questo modulo non
 * gestira' mai transazioni in quel formato (punto #19, confermato dal committente non
 * raggiungibile per costruzione), quindi la dispatch qui resta a due vie su entrambi i
 * lati. Nessuno dei due casi ibridi include il controllo sul debitore, fedele al legacy
 * (presente solo nel percorso V2-V2 puro, {@link #validaSemanticaV2}).</p>
 *
 * <p>Il multi-debitore di v3 non ha impatto su questa validazione: {@code
 * CtPaymentPaBuilder}/{@code CtPaymentPaV2Builder} risolvono gia' un solo "debitore
 * principale" al momento dell'ATTIVAZIONE della RPT, congelato nell'XML che questo
 * servizio rilegge — nessuna rilettura dinamica di {@code PosizioneDebitoria
 * #getSoggettiDebitori()} qui.</p>
 */
@Service
public class PaSendRTService {

	private final IntermediarioRepository intermediarioRepository;
	private final StazioneRepository stazioneRepository;
	private final DominioRepository dominioRepository;
	private final PendenzaRepository pendenzaRepository;
	private final RptRepository rptRepository;
	private final RptAttivazioneService rptAttivazioneService;
	private final AcquisizioneRicevutaService acquisizioneRicevutaService;
	private final Clock clock;
	private final ObjectFactory objectFactory = new ObjectFactory();
	private final JAXBContext jaxbContext;
	private final JAXBContext jaxbContextV2;

	public PaSendRTService(IntermediarioRepository intermediarioRepository, StazioneRepository stazioneRepository,
			DominioRepository dominioRepository, PendenzaRepository pendenzaRepository, RptRepository rptRepository,
			RptAttivazioneService rptAttivazioneService, AcquisizioneRicevutaService acquisizioneRicevutaService,
			Clock clock) {
		this.intermediarioRepository = intermediarioRepository;
		this.stazioneRepository = stazioneRepository;
		this.dominioRepository = dominioRepository;
		this.pendenzaRepository = pendenzaRepository;
		this.rptRepository = rptRepository;
		this.rptAttivazioneService = rptAttivazioneService;
		this.acquisizioneRicevutaService = acquisizioneRicevutaService;
		this.clock = clock;
		try {
			this.jaxbContext = JAXBContext.newInstance(PaSendRTReq.class);
			this.jaxbContextV2 = JAXBContext.newInstance(PaSendRTV2Request.class);
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * {@code noRollbackFor = NdpException.class}: necessario perche' {@link
	 * RptAttivazioneService#rifiutaPerValidazioneSemantica} (chiamato PRIMA del fault
	 * {@code PAA_SEMANTICA}) sopravviva — partecipa a QUESTA transazione (vedi Javadoc di
	 * quel metodo), che altrimenti farebbe rollback di quella scrittura insieme al resto
	 * non appena l'eccezione esce dal metodo. Sicuro perche' nessun'altra {@code
	 * NdpException} lanciata qui ha una scrittura pendente da preservare (sola lettura/
	 * lock fino a quel punto) — una futura modifica che introduca un'altra scrittura prima
	 * di un fault deve riverificare questa invariante.
	 */
	@Transactional(noRollbackFor = NdpException.class)
	public PaSendRTRes eseguiSendRT(PaSendRTReq request) {
		CtReceipt receipt = request.getReceipt();
		String codDominio = receipt.getFiscalCode();
		String iuv = receipt.getCreditorReferenceId();

		DominioEntity dominio = verificaAutorizzazione(request.getIdBrokerPA(), request.getIdStation(), codDominio);
		Pendenza pendenza = trovaEBloccaPendenza(dominio.getId(), iuv, codDominio);
		OffsetDateTime dataPagamento = convertiData(receipt.getPaymentDateTime());
		Rpt rpt = trovaRptInAttesa(dominio, pendenza, receipt.getReceiptId(), dataPagamento,
				RptAttivazioneService.VERSIONE_SANP_240, true, codDominio);
		byte[] xmlRt = serializza(request);

		if (rpt.getXmlRpt() != null) {
			try {
				if (Objects.equals(rpt.getVersione(), RptAttivazioneService.VERSIONE_SANP_240)) {
					CtPaymentPA richiesta = rptAttivazioneService.deserializzaDati(rpt);
					validaSemantica(richiesta, receipt, codDominio);
				} else {
					// Caso ibrido (punto #17 del design doc): RPT attivata con paGetPaymentV2,
					// ricevuta arrivata tramite paSendRT (V1).
					rpt.setVersione(RptAttivazioneService.VERSIONE_RPTV2_RTV1);
					CtPaymentPAV2 richiesta = rptAttivazioneService.deserializzaDatiV2(rpt);
					validaSemanticaIbridaV2RptV1Rt(richiesta, receipt, codDominio);
				}
			} catch (NdpException e) {
				rptAttivazioneService.rifiutaPerValidazioneSemantica(rpt, xmlRt, e.getDescrizione());
				throw e;
			}
		}

		DatiRicevutaPagamento dati = estraiDati(receipt.getReceiptId(), receipt.getOutcome() == StOutcome.OK,
				dataPagamento, receipt.getPaymentAmount(), receipt.getPspFiscalCode(),
				receipt.getPSPCompanyName(), receipt.getIdPSP(), receipt.getIdChannel(), receipt.getPaymentMethod(),
				xmlRt, trasferimentiV1(receipt));
		acquisizioneRicevutaService.acquisisci(pendenza, rpt, dati);

		PaSendRTRes response = new PaSendRTRes();
		response.setOutcome(StOutcome.OK);
		return response;
	}

	/** Come {@link #eseguiSendRT}, stesso motivo per {@code noRollbackFor}. */
	@Transactional(noRollbackFor = NdpException.class)
	public PaSendRTV2Response eseguiSendRTV2(PaSendRTV2Request request) {
		CtReceiptV2 receipt = request.getReceipt();
		String codDominio = receipt.getFiscalCode();
		String iuv = receipt.getCreditorReferenceId();

		DominioEntity dominio = verificaAutorizzazione(request.getIdBrokerPA(), request.getIdStation(), codDominio);
		Pendenza pendenza = trovaEBloccaPendenza(dominio.getId(), iuv, codDominio);
		OffsetDateTime dataPagamento = convertiData(receipt.getPaymentDateTime());
		Rpt rpt = trovaRptInAttesa(dominio, pendenza, receipt.getReceiptId(), dataPagamento,
				RptAttivazioneService.VERSIONE_SANP_321_V2, false, codDominio);
		byte[] xmlRt = serializzaV2(request);

		if (rpt.getXmlRpt() != null) {
			try {
				if (Objects.equals(rpt.getVersione(), RptAttivazioneService.VERSIONE_SANP_321_V2)) {
					CtPaymentPAV2 richiesta = rptAttivazioneService.deserializzaDatiV2(rpt);
					validaSemanticaV2(richiesta, receipt, codDominio);
				} else {
					// Caso ibrido (punto #18 del design doc): RPT attivata con paGetPayment (V1),
					// ricevuta arrivata tramite paSendRTV2. Nessun ramo SANP_230 (il legacy ne
					// avrebbe uno terzo, qui non raggiungibile per costruzione — punto #19).
					rpt.setVersione(RptAttivazioneService.VERSIONE_RPTV1_RTV2);
					CtPaymentPA richiesta = rptAttivazioneService.deserializzaDati(rpt);
					validaSemanticaIbridaV1RptV2Rt(richiesta, receipt, codDominio);
				}
			} catch (NdpException e) {
				rptAttivazioneService.rifiutaPerValidazioneSemantica(rpt, xmlRt, e.getDescrizione());
				throw e;
			}
		}

		DatiRicevutaPagamento dati = estraiDati(receipt.getReceiptId(), receipt.getOutcome() == StOutcome.OK,
				dataPagamento, receipt.getPaymentAmount(), receipt.getPspFiscalCode(),
				receipt.getPSPCompanyName(), receipt.getIdPSP(), receipt.getIdChannel(), receipt.getPaymentMethod(),
				xmlRt, trasferimentiV2(receipt));
		acquisizioneRicevutaService.acquisisci(pendenza, rpt, dati);

		PaSendRTV2Response response = new PaSendRTV2Response();
		response.setOutcome(StOutcome.OK);
		return response;
	}

	/** Stesso schema di {@code PaVerifyPaymentNoticeService#eseguiVerifica}: niente controllo dominio/stazione aggiuntivo. */
	private DominioEntity verificaAutorizzazione(String idBrokerPA, String idStation, String codDominio) {
		IntermediarioEntity intermediario = intermediarioRepository.findByCodIntermediario(idBrokerPA)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_ID_INTERMEDIARIO_ERRATO, codDominio));
		PaForNodeChecks.verificaPrincipalAutenticato(intermediario, codDominio);

		stazioneRepository.findByIntermediarioCodIntermediarioAndCodStazione(intermediario.getCodIntermediario(),
				idStation).orElseThrow(() -> new NdpException(FaultPa.PAA_STAZIONE_INT_ERRATA, codDominio));

		return dominioRepository.findByCodDominio(codDominio)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_ID_DOMINIO_ERRATO, codDominio));
	}

	private Pendenza trovaEBloccaPendenza(Long idDominio, String iuv, String codDominio) {
		Pendenza pendenza = pendenzaRepository.findByIdDominioAndIuv(idDominio, iuv)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_RPT_SCONOSCIUTA, codDominio));
		return pendenzaRepository.findByIdForUpdate(pendenza.getId())
				.orElseThrow(() -> new NdpException(FaultPa.PAA_RPT_SCONOSCIUTA, codDominio));
	}

	/**
	 * Idempotenza (gia' acquisita con questo {@code receiptId}) poi ricerca della RPT in
	 * attesa di ricevuta — in quest'ordine, cosi' un retry con lo stesso {@code receiptId}
	 * produce {@code PAA_RECEIPT_DUPLICATA} e non {@code PAA_RPT_SCONOSCIUTA} (la riga e'
	 * ormai in stato {@code RT_ACCETTATA_PA}, non piu' {@code RPT_ACCETTATA_NODO}).
	 *
	 * <p>{@code filtraPerModelloPsp} replica un'asimmetria presente anche nel legacy fra
	 * le due operazioni: {@code paSendRT} (V1, {@code CtReceiptUtils.acquisisciRT}) filtra
	 * la ricerca per modello {@code ATTIVATO_PRESSO_PSP}
	 * ({@code rptBD.getRpt(codDominio, iuv, ModelloPagamento.ATTIVATO_PRESSO_PSP, null)}),
	 * mentre {@code paSendRTV2} ({@code CtReceiptV2Utils.acquisisciRT}) NON filtra affatto
	 * ({@code rptBD.getRpt(codDominio, iuv, null, null)}) — su V2 una RPT di QUALUNQUE
	 * modello viene selezionata se e' nello stato atteso. Senza il filtro (quando
	 * richiesto, cioe' per V1), una RPT di un ALTRO modello ancora in questo stato
	 * verrebbe selezionata ed usata al posto della ricostruzione "standin".</p>
	 *
	 * <p>Se nessuna RPT in attesa esiste, ricade sul caso "standin" ({@link #standin})
	 * prima di arrendersi col fault — vedi Javadoc di classe.</p>
	 */
	private Rpt trovaRptInAttesa(DominioEntity dominio, Pendenza pendenza, String receiptId,
			OffsetDateTime dataPagamento, String versione, boolean filtraPerModelloPsp, String codDominioFault) {
		if (rptRepository.findByCodDominioAndIuvAndIur(dominio.getCodDominio(), pendenza.getIuv(), receiptId)
				.isPresent()) {
			throw new NdpException(FaultPa.PAA_RECEIPT_DUPLICATA, codDominioFault);
		}
		Optional<Rpt> inAttesa = filtraPerModelloPsp
				? rptRepository.findByIdVersamentoAndStatoAndModelloPagamento(pendenza.getId(),
						StatoRpt.RPT_ACCETTATA_NODO.name(), RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)
				: rptRepository.findByIdVersamentoAndStato(pendenza.getId(), StatoRpt.RPT_ACCETTATA_NODO.name());
		return inAttesa.orElseGet(
				() -> standin(dominio, pendenza, receiptId, dataPagamento, versione, filtraPerModelloPsp, codDominioFault));
	}

	/**
	 * Ricostruzione "standin" (vedi Javadoc di classe e di {@link #trovaRptInAttesa} per
	 * {@code filtraPerModelloPsp}): si applica solo se non esiste NESSUN'ALTRA RPT per
	 * questa pendenza (del modello {@code ATTIVATO_PRESSO_PSP} per V1, di qualunque
	 * modello per V2 — stessa asimmetria del legacy), di qualunque stato. Se invece esiste
	 * gia' una RPT che soddisfa questo criterio, resta {@code PAA_RPT_SCONOSCIUTA} come nel
	 * legacy, non si ricostruisce una seconda volta.
	 */
	private Rpt standin(DominioEntity dominio, Pendenza pendenza, String receiptId, OffsetDateTime dataPagamento,
			String versione, boolean filtraPerModelloPsp, String codDominioFault) {
		boolean esisteGiaUnaRpt = filtraPerModelloPsp
				? rptRepository.existsByIdVersamentoAndModelloPagamento(pendenza.getId(),
						RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP)
				: rptRepository.existsByIdVersamento(pendenza.getId());
		if (esisteGiaUnaRpt) {
			throw new NdpException(FaultPa.PAA_RPT_SCONOSCIUTA, codDominioFault);
		}
		return rptAttivazioneService.ricostruisci(pendenza, dominio, versione, receiptId, dataPagamento);
	}

	/**
	 * Quattro dei controlli "fatali" del legacy ({@code CtReceiptUtils.validaSemantica}):
	 * {@code creditorReferenceId}, coerenza del numero di trasferimenti con l'esito,
	 * importo totale pagato contro la somma dei singoli importi ricevuti, importo totale
	 * non nullo su un esito {@code KO}. Gli altri controlli del legacy sono solo
	 * diagnostici (producono un messaggio di log, non un fault) — non replicati, vedi
	 * Javadoc di classe.
	 */
	private void validaSemantica(CtPaymentPA richiesta, CtReceipt ricevuta, String codDominio) {
		if (!Objects.equals(richiesta.getCreditorReferenceId(), ricevuta.getCreditorReferenceId())) {
			throw new NdpException(FaultPa.PAA_SEMANTICA, "CreditorReferenceId non corrisponde", codDominio);
		}
		int numeroRichiesto = richiesta.getTransferList().getTransfer().size();
		List<CtTransferPA> trasferimenti = ricevuta.getTransferList() != null
				? ricevuta.getTransferList().getTransfer() : List.of();
		boolean numeroIncoerente = ricevuta.getOutcome() == StOutcome.OK
				? trasferimenti.size() != numeroRichiesto
				: !trasferimenti.isEmpty() && trasferimenti.size() != numeroRichiesto;
		if (numeroIncoerente) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"Numero di pagamenti diverso dal numero di versamenti", codDominio);
		}
		BigDecimal importoTotaleCalcolato = trasferimenti.stream().map(CtTransferPA::getTransferAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal paymentAmount = ricevuta.getPaymentAmount();
		if (importoTotaleCalcolato.compareTo(paymentAmount) != 0) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"ImportoTotalePagato non corrisponde alla somma dei SingoliImportiPagati", codDominio);
		}
		if (ricevuta.getOutcome() == StOutcome.KO && paymentAmount.compareTo(BigDecimal.ZERO) != 0) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"ImportoTotalePagato diverso da 0 per un pagamento con esito KO", codDominio);
		}
	}

	/**
	 * Come {@link #validaSemantica}, per {@code paSendRTV2} — con un controllo fatale IN
	 * PIU' presente solo in questa versione nel legacy
	 * ({@code CtReceiptV2Utils.validaSemantica}, via {@code NdpValidationUtils
	 * #validaSemantica(CtSubject,...)}): l'identificativo univoco del debitore.
	 */
	private void validaSemanticaV2(CtPaymentPAV2 richiesta, CtReceiptV2 ricevuta, String codDominio) {
		if (!Objects.equals(richiesta.getCreditorReferenceId(), ricevuta.getCreditorReferenceId())) {
			throw new NdpException(FaultPa.PAA_SEMANTICA, "CreditorReferenceId non corrisponde", codDominio);
		}
		String identificativoRichiesto = richiesta.getDebtor().getUniqueIdentifier().getEntityUniqueIdentifierValue();
		String identificativoRicevuto = ricevuta.getDebtor().getUniqueIdentifier().getEntityUniqueIdentifierValue();
		if (!Objects.equals(identificativoRichiesto, identificativoRicevuto)) {
			throw new NdpException(FaultPa.PAA_SEMANTICA, "UniqueIdentifierDebtor non corrisponde", codDominio);
		}
		int numeroRichiesto = richiesta.getTransferList().getTransfer().size();
		List<CtTransferPAReceiptV2> trasferimenti = ricevuta.getTransferList() != null
				? ricevuta.getTransferList().getTransfer() : List.of();
		boolean numeroIncoerente = ricevuta.getOutcome() == StOutcome.OK
				? trasferimenti.size() != numeroRichiesto
				: !trasferimenti.isEmpty() && trasferimenti.size() != numeroRichiesto;
		if (numeroIncoerente) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"Numero di pagamenti diverso dal numero di versamenti", codDominio);
		}
		BigDecimal importoTotaleCalcolato = trasferimenti.stream().map(CtTransferPAReceiptV2::getTransferAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal paymentAmount = ricevuta.getPaymentAmount();
		if (importoTotaleCalcolato.compareTo(paymentAmount) != 0) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"ImportoTotalePagato non corrisponde alla somma dei SingoliImportiPagati", codDominio);
		}
		if (ricevuta.getOutcome() == StOutcome.KO && paymentAmount.compareTo(BigDecimal.ZERO) != 0) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"ImportoTotalePagato diverso da 0 per un pagamento con esito KO", codDominio);
		}
	}

	/**
	 * Caso ibrido RPTV2_RTV1 (punto #17 del design doc) — porting di {@code
	 * CtReceiptUtils.validaSemantica(PaGetPaymentV2Response, PaSendRTReq)} del monolite:
	 * stessi quattro controlli di {@link #validaSemantica(CtPaymentPA, CtReceipt, String)}
	 * (la richiesta e' pero' nel formato V2), SENZA il controllo sul debitore — il legacy
	 * non lo fa in nessuno dei due casi ibridi, solo nel percorso V2-V2 puro (vedi {@link
	 * #validaSemanticaV2}).
	 */
	private void validaSemanticaIbridaV2RptV1Rt(CtPaymentPAV2 richiesta, CtReceipt ricevuta, String codDominio) {
		if (!Objects.equals(richiesta.getCreditorReferenceId(), ricevuta.getCreditorReferenceId())) {
			throw new NdpException(FaultPa.PAA_SEMANTICA, "CreditorReferenceId non corrisponde", codDominio);
		}
		int numeroRichiesto = richiesta.getTransferList().getTransfer().size();
		List<CtTransferPA> trasferimenti = ricevuta.getTransferList() != null
				? ricevuta.getTransferList().getTransfer() : List.of();
		boolean numeroIncoerente = ricevuta.getOutcome() == StOutcome.OK
				? trasferimenti.size() != numeroRichiesto
				: !trasferimenti.isEmpty() && trasferimenti.size() != numeroRichiesto;
		if (numeroIncoerente) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"Numero di pagamenti diverso dal numero di versamenti", codDominio);
		}
		BigDecimal importoTotaleCalcolato = trasferimenti.stream().map(CtTransferPA::getTransferAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal paymentAmount = ricevuta.getPaymentAmount();
		if (importoTotaleCalcolato.compareTo(paymentAmount) != 0) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"ImportoTotalePagato non corrisponde alla somma dei SingoliImportiPagati", codDominio);
		}
		if (ricevuta.getOutcome() == StOutcome.KO && paymentAmount.compareTo(BigDecimal.ZERO) != 0) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"ImportoTotalePagato diverso da 0 per un pagamento con esito KO", codDominio);
		}
	}

	/**
	 * Caso ibrido simmetrico RPTV1_RTV2 (punto #18 del design doc) — porting di {@code
	 * CtReceiptV2Utils.validaSemantica(PaGetPaymentRes, PaSendRTV2Request)} del monolite:
	 * stessi quattro controlli di {@link #validaSemanticaV2(CtPaymentPAV2, CtReceiptV2,
	 * String)} (la richiesta e' pero' nel formato V1), SENZA il controllo sul debitore —
	 * stessa asimmetria di {@link #validaSemanticaIbridaV2RptV1Rt}.
	 */
	private void validaSemanticaIbridaV1RptV2Rt(CtPaymentPA richiesta, CtReceiptV2 ricevuta, String codDominio) {
		if (!Objects.equals(richiesta.getCreditorReferenceId(), ricevuta.getCreditorReferenceId())) {
			throw new NdpException(FaultPa.PAA_SEMANTICA, "CreditorReferenceId non corrisponde", codDominio);
		}
		int numeroRichiesto = richiesta.getTransferList().getTransfer().size();
		List<CtTransferPAReceiptV2> trasferimenti = ricevuta.getTransferList() != null
				? ricevuta.getTransferList().getTransfer() : List.of();
		boolean numeroIncoerente = ricevuta.getOutcome() == StOutcome.OK
				? trasferimenti.size() != numeroRichiesto
				: !trasferimenti.isEmpty() && trasferimenti.size() != numeroRichiesto;
		if (numeroIncoerente) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"Numero di pagamenti diverso dal numero di versamenti", codDominio);
		}
		BigDecimal importoTotaleCalcolato = trasferimenti.stream().map(CtTransferPAReceiptV2::getTransferAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal paymentAmount = ricevuta.getPaymentAmount();
		if (importoTotaleCalcolato.compareTo(paymentAmount) != 0) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"ImportoTotalePagato non corrisponde alla somma dei SingoliImportiPagati", codDominio);
		}
		if (ricevuta.getOutcome() == StOutcome.KO && paymentAmount.compareTo(BigDecimal.ZERO) != 0) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"ImportoTotalePagato diverso da 0 per un pagamento con esito KO", codDominio);
		}
	}

	private List<DatiTrasferimentoRicevuto> trasferimentiV1(CtReceipt receipt) {
		List<DatiTrasferimentoRicevuto> trasferimenti = new ArrayList<>();
		if (receipt.getTransferList() != null) {
			for (CtTransferPA transfer : receipt.getTransferList().getTransfer()) {
				trasferimenti.add(new DatiTrasferimentoRicevuto(transfer.getIdTransfer(),
						transfer.getTransferAmount().doubleValue()));
			}
		}
		return trasferimenti;
	}

	private List<DatiTrasferimentoRicevuto> trasferimentiV2(CtReceiptV2 receipt) {
		List<DatiTrasferimentoRicevuto> trasferimenti = new ArrayList<>();
		if (receipt.getTransferList() != null) {
			for (CtTransferPAReceiptV2 transfer : receipt.getTransferList().getTransfer()) {
				trasferimenti.add(new DatiTrasferimentoRicevuto(transfer.getIdTransfer(),
						transfer.getTransferAmount().doubleValue()));
			}
		}
		return trasferimenti;
	}

	private DatiRicevutaPagamento estraiDati(String receiptId, boolean eseguito, OffsetDateTime dataPagamento,
			java.math.BigDecimal paymentAmount, String pspFiscalCode, String pspCompanyName, String idPSP,
			String idChannel, String paymentMethod, byte[] xmlRt, List<DatiTrasferimentoRicevuto> trasferimenti) {
		return new DatiRicevutaPagamento(receiptId, eseguito, dataPagamento,
				paymentAmount != null ? paymentAmount.doubleValue() : 0d, pspFiscalCode, pspCompanyName, idPSP,
				idChannel, paymentMethod, xmlRt, trasferimenti);
	}

	/** Stesso fallback-a-ora di prima: usato sia per {@link DatiRicevutaPagamento} sia per la ricostruzione "standin". */
	private OffsetDateTime convertiData(XMLGregorianCalendar paymentDateTime) {
		return paymentDateTime != null
				? paymentDateTime.toGregorianCalendar().toZonedDateTime().toOffsetDateTime()
				: OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
	}

	private byte[] serializza(PaSendRTReq request) {
		try {
			Marshaller marshaller = jaxbContext.createMarshaller();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			marshaller.marshal(objectFactory.createPaSendRTReq(request), output);
			return output.toByteArray();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}

	private byte[] serializzaV2(PaSendRTV2Request request) {
		try {
			Marshaller marshaller = jaxbContextV2.createMarshaller();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			marshaller.marshal(objectFactory.createPaSendRTV2Request(request), output);
			return output.toByteArray();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}
}
