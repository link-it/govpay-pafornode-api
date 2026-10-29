package it.govpay.pafornode.endpoint;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import javax.xml.datatype.XMLGregorianCalendar;

import jakarta.xml.bind.JAXBElement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentOptionDescriptionPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentOptionsDescriptionListPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtQrCode;
import it.gov.pagopa.pagopa_api.pa.pafornode.ObjectFactory;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.StAmountOption;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.CtFaultBean;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.rpt.PaGetPaymentActivationService;
import it.govpay.pafornode.rpt.UnitaOperativaResolver;
import it.govpay.pafornode.rpt.XmlDateUtils;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.service.CausaleVersamentoCodec;

/**
 * Implementa {@code paVerifyPaymentNotice} e {@code paGetPayment} del contratto paForNode.
 * Analisi completa del legacy ({@code PagamentiTelematiciCCPImpl}) nel design doc
 * (§1/§3/§3ter/§3quinquies). <b>Scope di questa prima versione</b>: gestisce solo pendenze
 * già presenti in {@code govpay-common-pendenze} (migrate o create dal percorso di scrittura
 * v3-nativo/wrapper v2) — l'acquisizione live dall'applicazione gestionale proprietaria
 * quando la pendenza non è ancora presente (§3/§3bis del design doc) non è ancora
 * implementata: una pendenza non trovata risulta {@code PAA_PAGAMENTO_SCONOSCIUTO}.
 * L'attualizzazione per validità scaduta (§3ter) e' implementata solo per {@code
 * paGetPayment} (via {@code PaGetPaymentActivationService}/{@code
 * AttualizzazionePendenzaService}), e solo nel ramo SEND — non per {@code
 * paVerifyPaymentNotice}, che resta {@code @Transactional(readOnly = true)} (vedi Javadoc di
 * {@code AttualizzazionePendenzaService}).
 */
@Endpoint
public class PaForNodeEndpoint {

	private static final String NAMESPACE = "http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd";

	private final DominioRepository dominioRepository;
	private final IntermediarioRepository intermediarioRepository;
	private final StazioneRepository stazioneRepository;
	private final UnitaOperativaRepository unitaOperativaRepository;
	private final PendenzaRepository pendenzaRepository;
	private final PagamentoRepository pagamentoRepository;
	private final PaGetPaymentActivationService paGetPaymentActivationService;
	private final Clock clock;
	private final List<String> navSondaPagoPA;
	private final ObjectFactory objectFactory = new ObjectFactory();

	public PaForNodeEndpoint(DominioRepository dominioRepository, IntermediarioRepository intermediarioRepository,
			StazioneRepository stazioneRepository, UnitaOperativaRepository unitaOperativaRepository,
			PendenzaRepository pendenzaRepository, PagamentoRepository pagamentoRepository,
			PaGetPaymentActivationService paGetPaymentActivationService, Clock clock,
			@Value("#{'${govpay.pafornode.sonda-nav:}'.split(',')}") List<String> navSondaPagoPA) {
		this.dominioRepository = dominioRepository;
		this.intermediarioRepository = intermediarioRepository;
		this.stazioneRepository = stazioneRepository;
		this.unitaOperativaRepository = unitaOperativaRepository;
		this.pendenzaRepository = pendenzaRepository;
		this.pagamentoRepository = pagamentoRepository;
		this.paGetPaymentActivationService = paGetPaymentActivationService;
		this.clock = clock;
		this.navSondaPagoPA = navSondaPagoPA;
	}

	@PayloadRoot(namespace = NAMESPACE, localPart = "paVerifyPaymentNoticeReq")
	@ResponsePayload
	@Transactional(readOnly = true)
	public JAXBElement<PaVerifyPaymentNoticeRes> paVerifyPaymentNotice(@RequestPayload PaVerifyPaymentNoticeReq request) {
		CtQrCode qrCode = request.getQrCode();
		String numeroAvviso = qrCode.getNoticeNumber();
		String codDominio = qrCode.getFiscalCode();
		PaVerifyPaymentNoticeRes response;
		try {
			response = eseguiVerifica(request, numeroAvviso, codDominio);
		} catch (NdpException e) {
			response = buildFault(e);
		} catch (RuntimeException e) {
			response = buildFault(new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e));
		}
		return objectFactory.createPaVerifyPaymentNoticeRes(response);
	}

	private PaVerifyPaymentNoticeRes eseguiVerifica(PaVerifyPaymentNoticeReq request, String numeroAvviso,
			String codDominio) {
		// Check sonda pagoPA PRIMA della verifica del dominio: un PAA_ID_DOMINIO_ERRATO qui
		// verrebbe interpretato da Stand-In come disservizio reale (stesso principio del
		// legacy).
		if (navSondaPagoPA.contains(numeroAvviso)) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, codDominio);
		}

		IntermediarioEntity intermediario = intermediarioRepository.findByCodIntermediario(request.getIdBrokerPA())
				.orElseThrow(() -> new NdpException(FaultPa.PAA_ID_INTERMEDIARIO_ERRATO, codDominio));
		PaForNodeChecks.verificaPrincipalAutenticato(intermediario, codDominio);

		StazioneEntity stazione = stazioneRepository
				.findByIntermediarioCodIntermediarioAndCodStazione(intermediario.getCodIntermediario(),
						request.getIdStation())
				.orElseThrow(() -> new NdpException(FaultPa.PAA_STAZIONE_INT_ERRATA, codDominio));

		DominioEntity dominio = dominioRepository.findByCodDominio(codDominio)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_ID_DOMINIO_ERRATO, codDominio));

		String iuv = PaForNodeChecks.convertiNumeroAvvisoInIuv(numeroAvviso, dominio, codDominio);

		Pendenza pendenza = pendenzaRepository.findByIdDominioAndIuv(dominio.getId(), iuv)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, codDominio));

		PaForNodeChecks.verificaStatoPagabile(pendenza, codDominio, clock, pagamentoRepository);

		return buildRispostaPositiva(pendenza, dominio);
	}

	/**
	 * A differenza di {@code paVerifyPaymentNotice} (sola verifica), attiva una RPT:
	 * costruisce il {@code CtPaymentPA}, la persiste (annullando eventuali RPT pendenti sulla
	 * stessa coppia dominio/iuv) e accoda la notifica di attivazione all'applicazione.
	 * Rifiuta a monte una pendenza con marca da bollo telematica (non ammessa su questa
	 * primitiva, a differenza di {@code paGetPaymentV2}).
	 *
	 * <p>Non transazionale: l'intera operazione (verifica + attivazione) e' transazionale
	 * dentro {@link PaGetPaymentActivationService}, cosi' il commit avviene PRIMA che questo
	 * metodo decida la risposta — un errore al commit va quindi nel catch sotto, non scappa
	 * come SOAP Fault grezzo dopo che la risposta era gia' stata costruita.</p>
	 */
	@PayloadRoot(namespace = NAMESPACE, localPart = "paGetPaymentReq")
	@ResponsePayload
	public JAXBElement<PaGetPaymentRes> paGetPayment(@RequestPayload PaGetPaymentReq request) {
		CtQrCode qrCode = request.getQrCode();
		String numeroAvviso = qrCode.getNoticeNumber();
		String codDominio = qrCode.getFiscalCode();
		PaGetPaymentRes response;
		try {
			response = paGetPaymentActivationService.eseguiGetPayment(request, numeroAvviso, codDominio);
		} catch (NdpException e) {
			response = buildFaultGetPayment(e);
		} catch (RuntimeException e) {
			response = buildFaultGetPayment(new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e));
		}
		return objectFactory.createPaGetPaymentRes(response);
	}

	private static PaGetPaymentRes buildFaultGetPayment(NdpException e) {
		PaGetPaymentRes response = new PaGetPaymentRes();
		response.setOutcome(StOutcome.KO);
		CtFaultBean fault = new CtFaultBean();
		fault.setId(e.getCodDominio());
		fault.setFaultCode(e.getFault().name());
		fault.setFaultString(e.getFault().getFaultString());
		fault.setDescription(e.getDescrizione());
		response.setFault(fault);
		return response;
	}

	private PaVerifyPaymentNoticeRes buildRispostaPositiva(Pendenza pendenza, DominioEntity dominio) {
		PosizioneDebitoria posizione = pendenza.getOpzionePagamento().getPosizioneDebitoria();
		String causale = CausaleVersamentoCodec.decode(pendenza.getCausaleVersamento());
		String descrizione = causale != null ? causale : posizione.getDescrizione();

		PaVerifyPaymentNoticeRes response = new PaVerifyPaymentNoticeRes();
		response.setOutcome(StOutcome.OK);
		response.setFiscalCodePA(dominio.getCodDominio());
		response.setCompanyName(dominio.getRagioneSociale());
		response.setOfficeName(UnitaOperativaResolver.risolviNome(unitaOperativaRepository, posizione));
		response.setPaymentDescription(descrizione);

		CtPaymentOptionDescriptionPA opzione = new CtPaymentOptionDescriptionPA();
		opzione.setOptions(StAmountOption.EQ);
		opzione.setAmount(importoTotaleConSend(pendenza));
		opzione.setDetailDescription(descrizione);
		opzione.setDueDate(toXmlDate(pendenza.getDataValidita()));
		// TODO allCCP: richiede di verificare che tutte le voci abbiano un IBAN di accredito
		// postale (equivalente di VersamentoUtils#isAllIBANPostali) — non ancora implementato.
		opzione.setAllCCP(false);

		CtPaymentOptionsDescriptionListPA paymentList = new CtPaymentOptionsDescriptionListPA();
		paymentList.setPaymentOptionDescription(opzione);
		response.setPaymentList(paymentList);
		return response;
	}

	private static BigDecimal importoTotaleConSend(Pendenza pendenza) {
		BigDecimal importo = BigDecimal.valueOf(pendenza.getImporto());
		return pendenza.getSendImportoTotale() != null
				? importo.add(BigDecimal.valueOf(pendenza.getSendImportoTotale())) : importo;
	}

	private static XMLGregorianCalendar toXmlDate(OffsetDateTime data) {
		return data == null ? null : XmlDateUtils.toXmlDate(data.toLocalDate());
	}

	private static PaVerifyPaymentNoticeRes buildFault(NdpException e) {
		PaVerifyPaymentNoticeRes response = new PaVerifyPaymentNoticeRes();
		response.setOutcome(StOutcome.KO);
		CtFaultBean fault = new CtFaultBean();
		fault.setId(e.getCodDominio());
		fault.setFaultCode(e.getFault().name());
		fault.setFaultString(e.getFault().getFaultString());
		fault.setDescription(e.getDescrizione());
		response.setFault(fault);
		return response;
	}
}
