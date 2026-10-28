package it.govpay.pafornode.rpt;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.pafornode.endpoint.PaForNodeChecks;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;

/**
 * Intera operazione {@code paGetPayment} (V1): verifica + attivazione RPT + notifica, in
 * un'unica transazione gestita qui — non nell'endpoint. L'endpoint resta non transazionale e
 * si limita a chiamare questo servizio dentro il proprio try/catch: se @Transactional fosse
 * sull'endpoint, il commit avverrebbe DOPO il ritorno del metodo (quindi dopo il
 * try/catch), e un errore al commit (es. collisione sulla chiave della RPT) scapperebbe alla
 * costruzione della risposta applicativa KO, arrivando al chiamante come HTTP 500/SOAP Fault
 * grezzo invece che come {@code PAA_SYSTEM_ERROR}.
 */
@Service
public class PaGetPaymentActivationService {

	private final DominioRepository dominioRepository;
	private final IntermediarioRepository intermediarioRepository;
	private final StazioneRepository stazioneRepository;
	private final PendenzaRepository pendenzaRepository;
	private final PagamentoRepository pagamentoRepository;
	private final CtPaymentPaBuilder ctPaymentPaBuilder;
	private final RptAttivazioneService rptAttivazioneService;
	private final NotificaAttivazioneService notificaAttivazioneService;
	private final Clock clock;

	public PaGetPaymentActivationService(DominioRepository dominioRepository,
			IntermediarioRepository intermediarioRepository, StazioneRepository stazioneRepository,
			PendenzaRepository pendenzaRepository, PagamentoRepository pagamentoRepository,
			CtPaymentPaBuilder ctPaymentPaBuilder, RptAttivazioneService rptAttivazioneService,
			NotificaAttivazioneService notificaAttivazioneService, Clock clock) {
		this.dominioRepository = dominioRepository;
		this.intermediarioRepository = intermediarioRepository;
		this.stazioneRepository = stazioneRepository;
		this.pendenzaRepository = pendenzaRepository;
		this.pagamentoRepository = pagamentoRepository;
		this.ctPaymentPaBuilder = ctPaymentPaBuilder;
		this.rptAttivazioneService = rptAttivazioneService;
		this.notificaAttivazioneService = notificaAttivazioneService;
		this.clock = clock;
	}

	@Transactional
	public PaGetPaymentRes eseguiGetPayment(PaGetPaymentReq request, String numeroAvviso, String codDominio) {
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

		if (contieneMarcaDaBollo(pendenza)) {
			throw new NdpException(FaultPa.PAA_SEMANTICA,
					"La pendenza contiene una marca da bollo telematica, non ammessa per pagamenti con la primitiva paGetPayment.",
					codDominio);
		}

		CtPaymentPA ctPayment = ctPaymentPaBuilder.costruisci(pendenza, dominio, request.getTransferType());
		String ccp = Long.toString(clock.millis());
		Rpt rpt = rptAttivazioneService.attiva(pendenza, dominio, stazione, ccp, ctPayment);
		notificaAttivazioneService.accoda(pendenza, rpt);

		PaGetPaymentRes response = new PaGetPaymentRes();
		response.setOutcome(StOutcome.OK);
		response.setData(ctPayment);
		return response;
	}

	/** Marca da bollo telematica: non ammessa su questa primitiva (solo su paGetPaymentV2). */
	private static boolean contieneMarcaDaBollo(Pendenza pendenza) {
		for (VocePendenza voce : pendenza.getVoci()) {
			if (voce.getHashDocumento() != null && voce.getProvinciaResidenza() != null
					&& voce.getTipoBollo() != null) {
				return true;
			}
		}
		return false;
	}
}
