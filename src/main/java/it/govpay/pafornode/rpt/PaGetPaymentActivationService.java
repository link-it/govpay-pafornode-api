package it.govpay.pafornode.rpt;

import java.time.Clock;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.ec.client.beans.PendenzaVerificata;
import it.govpay.pafornode.endpoint.PaForNodeChecks;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.verifica.ApplicazioneDominioResolver;
import it.govpay.pafornode.verifica.PendenzaVerificataMapper;
import it.govpay.pafornode.verifica.VerificaClient;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * Intera operazione {@code paGetPayment} (V1): verifica + attivazione RPT + notifica, in
 * un'unica transazione gestita qui — non nell'endpoint. L'endpoint resta non transazionale e
 * si limita a chiamare questo servizio dentro il proprio try/catch: se @Transactional fosse
 * sull'endpoint, il commit avverrebbe DOPO il ritorno del metodo (quindi dopo il
 * try/catch), e un errore al commit (es. collisione sulla chiave della RPT) scapperebbe alla
 * costruzione della risposta applicativa KO, arrivando al chiamante come HTTP 500/SOAP Fault
 * grezzo invece che come {@code PAA_SYSTEM_ERROR}.
 *
 * <p>Include anche l'attualizzazione per validita' scaduta ({@link AttualizzazionePendenzaService},
 * §3ter del design doc) prima dei controlli di pagabilita', nello scope che quel servizio
 * implementa oggi.
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
	private final AttualizzazionePendenzaService attualizzazionePendenzaService;
	private final ApplicazioneDominioResolver applicazioneDominioResolver;
	private final VerificaClient verificaClient;
	private final PendenzaVerificataMapper pendenzaVerificataMapper;
	private final PosizioneDebitoriaService posizioneDebitoriaService;
	private final Clock clock;
	private final EntityManager entityManager;

	public PaGetPaymentActivationService(DominioRepository dominioRepository,
			IntermediarioRepository intermediarioRepository, StazioneRepository stazioneRepository,
			PendenzaRepository pendenzaRepository, PagamentoRepository pagamentoRepository,
			CtPaymentPaBuilder ctPaymentPaBuilder, RptAttivazioneService rptAttivazioneService,
			NotificaAttivazioneService notificaAttivazioneService,
			AttualizzazionePendenzaService attualizzazionePendenzaService,
			ApplicazioneDominioResolver applicazioneDominioResolver, VerificaClient verificaClient,
			PendenzaVerificataMapper pendenzaVerificataMapper, PosizioneDebitoriaService posizioneDebitoriaService,
			Clock clock, EntityManager entityManager) {
		this.dominioRepository = dominioRepository;
		this.intermediarioRepository = intermediarioRepository;
		this.stazioneRepository = stazioneRepository;
		this.pendenzaRepository = pendenzaRepository;
		this.pagamentoRepository = pagamentoRepository;
		this.ctPaymentPaBuilder = ctPaymentPaBuilder;
		this.rptAttivazioneService = rptAttivazioneService;
		this.notificaAttivazioneService = notificaAttivazioneService;
		this.attualizzazionePendenzaService = attualizzazionePendenzaService;
		this.applicazioneDominioResolver = applicazioneDominioResolver;
		this.verificaClient = verificaClient;
		this.pendenzaVerificataMapper = pendenzaVerificataMapper;
		this.posizioneDebitoriaService = posizioneDebitoriaService;
		this.clock = clock;
		this.entityManager = entityManager;
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

		Optional<Pendenza> pendenzaEsistente = pendenzaRepository.findByIdDominioAndIuv(dominio.getId(), iuv);
		Pendenza pendenza;
		if (pendenzaEsistente.isPresent()) {
			pendenza = pendenzaEsistente.get();
			// La prima lettura serve a individuare la pendenza. Rileggila sotto lock prima
			// dei controlli: un'altra transazione potrebbe averla pagata/annullata nel
			// frattempo. Un semplice lock non aggiorna lo stato gia' nel persistence
			// context. Il lock rimane acquisito fino al commit di attivazione RPT e
			// notifica.
			entityManager.refresh(pendenza, LockModeType.PESSIMISTIC_WRITE);
			attualizzazionePendenzaService.attualizza(pendenza, dominio, codDominio);
		} else {
			// Pendenza del tutto assente: nessun lock da (ri)acquisire, nessuna
			// attualizzazione da ripetere — e' stata appena creata in questa stessa
			// transazione, nessun'altra puo' vederla finche' non committa.
			pendenza = acquisisciPendenzaAssente(dominio, iuv, numeroAvviso, codDominio);
		}

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
		// Non si riusa l'oggetto 'ctPayment' in memoria: si rilegge dall'XML appena
		// persistito, stessa garanzia di coerenza-con-quanto-scritto del legacy
		// (JaxbUtils.toPaGetPaymentResRPT su rpt.getXmlRpt()).
		response.setData(rptAttivazioneService.deserializzaDati(rpt));
		return response;
	}

	/**
	 * Pendenza DEL TUTTO ASSENTE (§3quinquies/punto aperto #10 del design doc): risolve quale
	 * applicazione gestisce questo dominio/iuv ({@link ApplicazioneDominioResolver}), acquisisce
	 * la pendenza dal suo connettore di verifica e la crea (non un aggiornamento, non esiste
	 * ancora nulla da aggiornare).
	 */
	private Pendenza acquisisciPendenzaAssente(DominioEntity dominio, String iuv, String numeroAvviso,
			String codDominio) {
		ApplicazioneEntity applicazione = applicazioneDominioResolver.risolvi(dominio, iuv)
				.orElseThrow(() -> new NdpException(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, codDominio));
		String codConnettoreVerifica = applicazione.getCodConnettoreIntegrazione();
		if (codConnettoreVerifica == null || codConnettoreVerifica.isBlank()) {
			throw new NdpException(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, codDominio);
		}
		PendenzaVerificata risposta = verificaClient.getAvviso(codConnettoreVerifica, dominio.getCodDominio(),
				numeroAvviso);
		PendenzaVerificataMapper.verificaEsito(risposta, codDominio);
		PendenzaVerificataMapper.verificaIdentita(risposta.getPendenza(), dominio.getCodDominio(), numeroAvviso,
				codDominio);
		DatiPosizioneDebitoria comando = pendenzaVerificataMapper.creaDatiPosizioneDebitoria(risposta.getPendenza(),
				applicazione.getId(), dominio.getId(), numeroAvviso);
		PosizioneDebitoria creata = posizioneDebitoriaService.crea(comando);
		return creata.getOpzioniPagamento().get(0).getPendenze().get(0);
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
