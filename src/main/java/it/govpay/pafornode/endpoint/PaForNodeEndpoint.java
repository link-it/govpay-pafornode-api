package it.govpay.pafornode.endpoint;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
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
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.StAmountOption;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.CtFaultBean;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.common.utils.IuvUtils;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.service.CausaleVersamentoCodec;

/**
 * Implementa {@code paVerifyPaymentNotice} del contratto paForNode. Analisi completa del
 * legacy ({@code PagamentiTelematiciCCPImpl#paVerifyPaymentNotice}) nel design doc
 * (§1/§3/§3ter). <b>Scope di questa prima versione</b>: gestisce solo pendenze già presenti
 * in {@code govpay-common-pendenze} (migrate o create dal percorso di scrittura v3-nativo/
 * wrapper v2) — l'acquisizione live dall'applicazione gestionale proprietaria quando la
 * pendenza non è ancora presente (§3/§3bis del design doc) non è ancora implementata, nè lo
 * è l'attualizzazione per validità scaduta che la richiederebbe (§3ter): una pendenza non
 * trovata risulta {@code PAA_PAGAMENTO_SCONOSCIUTO}.
 */
@Endpoint
public class PaForNodeEndpoint {

	private static final String NAMESPACE = "http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd";
	private static final String COD_UNITA_OPERATIVA_EC = "EC";

	private final DominioRepository dominioRepository;
	private final IntermediarioRepository intermediarioRepository;
	private final StazioneRepository stazioneRepository;
	private final UnitaOperativaRepository unitaOperativaRepository;
	private final PendenzaRepository pendenzaRepository;
	private final Clock clock;
	private final List<String> navSondaPagoPA;
	private final ObjectFactory objectFactory = new ObjectFactory();

	public PaForNodeEndpoint(DominioRepository dominioRepository, IntermediarioRepository intermediarioRepository,
			StazioneRepository stazioneRepository, UnitaOperativaRepository unitaOperativaRepository,
			PendenzaRepository pendenzaRepository, Clock clock,
			@Value("#{'${govpay.pafornode.sonda-nav:}'.split(',')}") List<String> navSondaPagoPA) {
		this.dominioRepository = dominioRepository;
		this.intermediarioRepository = intermediarioRepository;
		this.stazioneRepository = stazioneRepository;
		this.unitaOperativaRepository = unitaOperativaRepository;
		this.pendenzaRepository = pendenzaRepository;
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
		StazioneEntity stazione = stazioneRepository
				.findByIntermediarioCodIntermediarioAndCodStazione(intermediario.getCodIntermediario(),
						request.getIdStation())
				.orElseThrow(() -> new NdpException(FaultPa.PAA_STAZIONE_INT_ERRATA, codDominio));

		DominioEntity dominio = dominioRepository.findByCodDominio(codDominio)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_ID_DOMINIO_ERRATO, codDominio));

		String iuv = convertiNumeroAvvisoInIuv(numeroAvviso, dominio, codDominio);

		Pendenza pendenza = pendenzaRepository.findByIdDominioAndIuv(dominio.getId(), iuv)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, codDominio));

		verificaStatoPagabile(pendenza, codDominio);

		return buildRispostaPositiva(pendenza, dominio);
	}

	/**
	 * {@code numeroAvviso} si converte in IUV solo conoscendo l'AuxDigit configurato sul
	 * dominio (diversamente dal legacy, che derivava lo IUV per formato prima ancora di
	 * risolvere il dominio — qui il dominio va risolto prima, §3ter del design doc).
	 */
	private String convertiNumeroAvvisoInIuv(String numeroAvviso, DominioEntity dominio, String codDominio) {
		Integer applicationCode = dominio.getStazione() == null ? null : dominio.getStazione().getApplicationCode();
		try {
			return IuvUtils.convertiDaNumeroAvviso(numeroAvviso, dominio.getAuxDigit(), dominio.getSegregationCode(),
					applicationCode);
		} catch (IllegalArgumentException e) {
			throw new NdpException(FaultPa.PAA_SYSTEM_ERROR, e.getMessage(), codDominio, e);
		}
	}

	/**
	 * {@code ANNULLATO}/{@code ESEGUITO}/{@code ESEGUITO_ALTRO_CANALE}/scadenza superata →
	 * fault. Non tenta l'attualizzazione per validità scaduta (richiederebbe l'acquisizione
	 * live, non ancora implementata): una pendenza con validità decorsa ma non scaduta viene
	 * comunque restituita con i dati correnti.
	 */
	private void verificaStatoPagabile(Pendenza pendenza, String codDominio) {
		if (pendenza.getStato() == StatoPendenza.ANNULLATO) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_ANNULLATO, codDominio);
		}
		if (pendenza.getStato() == StatoPendenza.ESEGUITO || pendenza.getStato() == StatoPendenza.ESEGUITO_ALTRO_CANALE) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_DUPLICATO, codDominio);
		}
		OffsetDateTime scadenza = pendenza.getDataScadenzaAvviso();
		if (scadenza != null && scadenza.isBefore(OffsetDateTime.now(clock))) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCADUTO, codDominio);
		}
	}

	private PaVerifyPaymentNoticeRes buildRispostaPositiva(Pendenza pendenza, DominioEntity dominio) {
		PosizioneDebitoria posizione = pendenza.getOpzionePagamento().getPosizioneDebitoria();
		String causale = CausaleVersamentoCodec.decode(pendenza.getCausaleVersamento());
		String descrizione = causale != null ? causale : posizione.getDescrizione();

		PaVerifyPaymentNoticeRes response = new PaVerifyPaymentNoticeRes();
		response.setOutcome(StOutcome.OK);
		response.setFiscalCodePA(dominio.getCodDominio());
		response.setCompanyName(dominio.getRagioneSociale());
		response.setOfficeName(risolviNomeUnitaOperativa(posizione));
		response.setPaymentDescription(descrizione);

		CtPaymentOptionDescriptionPA opzione = new CtPaymentOptionDescriptionPA();
		opzione.setOptions(StAmountOption.EQ);
		opzione.setAmount(BigDecimal.valueOf(pendenza.getImporto()));
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

	/** {@code null} se l'unità operativa non è configurata o è l'ente creditore stesso (EC). */
	private String risolviNomeUnitaOperativa(PosizioneDebitoria posizione) {
		if (posizione.getIdUnitaOperativa() == null) {
			return null;
		}
		Optional<UnitaOperativaEntity> unitaOperativa = unitaOperativaRepository
				.findById(posizione.getIdUnitaOperativa());
		return unitaOperativa.filter(uo -> !COD_UNITA_OPERATIVA_EC.equals(uo.getCodUo()))
				.map(UnitaOperativaEntity::getUoDenominazione).orElse(null);
	}

	private static XMLGregorianCalendar toXmlDate(OffsetDateTime data) {
		if (data == null) {
			return null;
		}
		LocalDate localDate = data.toLocalDate();
		try {
			return DatatypeFactory.newInstance().newXMLGregorianCalendarDate(localDate.getYear(),
					localDate.getMonthValue(), localDate.getDayOfMonth(), javax.xml.datatype.DatatypeConstants.FIELD_UNDEFINED);
		} catch (DatatypeConfigurationException e) {
			throw new IllegalStateException(e);
		}
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
