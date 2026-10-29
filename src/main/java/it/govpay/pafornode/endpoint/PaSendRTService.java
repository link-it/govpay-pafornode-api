package it.govpay.pafornode.endpoint;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * <p><b>Punti aperti, non implementati qui</b> (vedi Javadoc di classe di
 * {@link AcquisizioneRicevutaService} e punto aperto #14 del design doc):
 * ricostruzione "standin" di un'RPT mai attivata da GovPay ({@code
 * CtReceiptUtils.ricostruisciRPT} nel monolite — qui una RPT non trovata e' sempre
 * {@code PAA_RPT_SCONOSCIUTA}); validazione semantica semplificata (solo i due controlli
 * "fatali" del legacy — {@code creditorReferenceId} e coerenza del numero di
 * trasferimenti — non l'intero confronto campo per campo, incluso il caso ibrido
 * {@code RPTV2_RTV1}); nessuna persistenza separata dello stato {@code RT_RIFIUTATA_PA}
 * quando la validazione semantica fallisce (il legacy la commit a parte prima di
 * rilanciare il fault, qui l'intera transazione viene scartata col fault).</p>
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

	@Transactional
	public PaSendRTRes eseguiSendRT(PaSendRTReq request) {
		CtReceipt receipt = request.getReceipt();
		String codDominio = receipt.getFiscalCode();
		String iuv = receipt.getCreditorReferenceId();

		DominioEntity dominio = verificaAutorizzazione(request.getIdBrokerPA(), request.getIdStation(), codDominio);
		Pendenza pendenza = trovaEBloccaPendenza(dominio.getId(), iuv, codDominio);
		Rpt rpt = trovaRptInAttesa(dominio.getCodDominio(), pendenza, receipt.getReceiptId(), codDominio);

		CtPaymentPA richiesta = rptAttivazioneService.deserializzaDati(rpt);
		validaSemantica(richiesta, receipt, codDominio);

		DatiRicevutaPagamento dati = estraiDati(receipt.getReceiptId(), receipt.getOutcome() == StOutcome.OK,
				receipt.getPaymentDateTime(), receipt.getPaymentAmount(), receipt.getPspFiscalCode(),
				receipt.getPSPCompanyName(), receipt.getIdPSP(), receipt.getIdChannel(), receipt.getPaymentMethod(),
				serializza(request), trasferimentiV1(receipt));
		acquisizioneRicevutaService.acquisisci(pendenza, rpt, dati);

		PaSendRTRes response = new PaSendRTRes();
		response.setOutcome(StOutcome.OK);
		return response;
	}

	@Transactional
	public PaSendRTV2Response eseguiSendRTV2(PaSendRTV2Request request) {
		CtReceiptV2 receipt = request.getReceipt();
		String codDominio = receipt.getFiscalCode();
		String iuv = receipt.getCreditorReferenceId();

		DominioEntity dominio = verificaAutorizzazione(request.getIdBrokerPA(), request.getIdStation(), codDominio);
		Pendenza pendenza = trovaEBloccaPendenza(dominio.getId(), iuv, codDominio);
		Rpt rpt = trovaRptInAttesa(dominio.getCodDominio(), pendenza, receipt.getReceiptId(), codDominio);

		CtPaymentPAV2 richiesta = rptAttivazioneService.deserializzaDatiV2(rpt);
		validaSemanticaV2(richiesta, receipt, codDominio);

		DatiRicevutaPagamento dati = estraiDati(receipt.getReceiptId(), receipt.getOutcome() == StOutcome.OK,
				receipt.getPaymentDateTime(), receipt.getPaymentAmount(), receipt.getPspFiscalCode(),
				receipt.getPSPCompanyName(), receipt.getIdPSP(), receipt.getIdChannel(), receipt.getPaymentMethod(),
				serializzaV2(request), trasferimentiV2(receipt));
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
	 */
	private Rpt trovaRptInAttesa(String codDominio, Pendenza pendenza, String receiptId, String codDominioFault) {
		if (rptRepository.findByCodDominioAndIuvAndIur(codDominio, pendenza.getIuv(), receiptId).isPresent()) {
			throw new NdpException(FaultPa.PAA_RECEIPT_DUPLICATA, codDominioFault);
		}
		return rptRepository.findByIdVersamentoAndStato(pendenza.getId(), StatoRpt.RPT_ACCETTATA_NODO.name())
				.orElseThrow(() -> new NdpException(FaultPa.PAA_RPT_SCONOSCIUTA, codDominioFault));
	}

	/**
	 * Solo i due controlli "fatali" del legacy ({@code CtReceiptUtils.validaSemantica},
	 * righe 96/102-105): {@code creditorReferenceId} e coerenza del numero di
	 * trasferimenti con l'esito. Gli altri controlli del legacy sono solo diagnostici
	 * (producono un messaggio di log, non un fault) — non replicati, vedi Javadoc di classe.
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
	}

	/** Come {@link #validaSemantica}, per {@code paSendRTV2}. */
	private void validaSemanticaV2(CtPaymentPAV2 richiesta, CtReceiptV2 ricevuta, String codDominio) {
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

	private DatiRicevutaPagamento estraiDati(String receiptId, boolean eseguito,
			XMLGregorianCalendar paymentDateTime, java.math.BigDecimal paymentAmount, String pspFiscalCode,
			String pspCompanyName, String idPSP, String idChannel, String paymentMethod, byte[] xmlRt,
			List<DatiTrasferimentoRicevuto> trasferimenti) {
		OffsetDateTime dataPagamento = paymentDateTime != null
				? paymentDateTime.toGregorianCalendar().toZonedDateTime().toOffsetDateTime()
				: OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
		return new DatiRicevutaPagamento(receiptId, eseguito, dataPagamento,
				paymentAmount != null ? paymentAmount.doubleValue() : 0d, pspFiscalCode, pspCompanyName, idPSP,
				idChannel, paymentMethod, xmlRt, trasferimenti);
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
