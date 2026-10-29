package it.govpay.pafornode.rpt;

import java.time.Clock;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Request;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Response;
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
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * Intera operazione {@code paGetPaymentV2}: verifica + attivazione RPT + notifica, stessa
 * struttura transazionale di {@link PaGetPaymentActivationService} (V1) — stesso motivo:
 * l'endpoint resta non transazionale, l'intera operazione e' qui.
 *
 * <p>Analisi completa del legacy nel design doc: {@code paGetPaymentV2}
 * (`PagamentiTelematiciCCPImpl.java:1283-1591`) e' identico a {@code paGetPayment} fino alla
 * risoluzione/attualizzazione della pendenza (stesso codice, verificato riga per riga) — da
 * qui tutti i servizi condivisi (controlli, attualizzazione, risoluzione applicazione) sono
 * riusati senza modifiche. Le uniche differenze reali: nessun rifiuto preventivo della
 * marca da bollo (gestita per transfer da {@link CtPaymentPaV2Builder}), builder/versione
 * RPT/risposta V2 ({@link RptAttivazioneService#attivaV2}).
 */
@Service
public class PaGetPaymentV2ActivationService {

	private static final int LUNGHEZZA_MASSIMA_COD_CARRELLO = 35;

	private final DominioRepository dominioRepository;
	private final IntermediarioRepository intermediarioRepository;
	private final StazioneRepository stazioneRepository;
	private final PendenzaRepository pendenzaRepository;
	private final PagamentoRepository pagamentoRepository;
	private final CtPaymentPaV2Builder ctPaymentPaV2Builder;
	private final RptAttivazioneService rptAttivazioneService;
	private final NotificaAttivazioneService notificaAttivazioneService;
	private final AttualizzazionePendenzaService attualizzazionePendenzaService;
	private final ApplicazioneDominioResolver applicazioneDominioResolver;
	private final VerificaClient verificaClient;
	private final PendenzaVerificataMapper pendenzaVerificataMapper;
	private final PosizioneDebitoriaService posizioneDebitoriaService;
	private final Clock clock;
	private final EntityManager entityManager;

	public PaGetPaymentV2ActivationService(DominioRepository dominioRepository,
			IntermediarioRepository intermediarioRepository, StazioneRepository stazioneRepository,
			PendenzaRepository pendenzaRepository, PagamentoRepository pagamentoRepository,
			CtPaymentPaV2Builder ctPaymentPaV2Builder, RptAttivazioneService rptAttivazioneService,
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
		this.ctPaymentPaV2Builder = ctPaymentPaV2Builder;
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
	public PaGetPaymentV2Response eseguiGetPaymentV2(PaGetPaymentV2Request request, String numeroAvviso,
			String codDominio) {
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
			entityManager.refresh(pendenza, LockModeType.PESSIMISTIC_WRITE);
			attualizzazionePendenzaService.attualizza(pendenza, dominio, codDominio);
		} else {
			pendenza = acquisisciPendenzaAssente(dominio, iuv, numeroAvviso, codDominio);
		}

		PaForNodeChecks.verificaStatoPagabile(pendenza, codDominio, clock, pagamentoRepository);

		CtPaymentPAV2 ctPayment = ctPaymentPaV2Builder.costruisci(pendenza, dominio, request.getTransferType());
		String ccp = Long.toString(clock.millis());
		String codCarrello = troncaCodCarrello(request.getPaymentNote());
		Rpt rpt = rptAttivazioneService.attivaV2(pendenza, dominio, stazione, ccp, codCarrello, ctPayment);
		notificaAttivazioneService.accoda(pendenza, rpt);

		PaGetPaymentV2Response response = new PaGetPaymentV2Response();
		response.setOutcome(StOutcome.OK);
		response.setData(rptAttivazioneService.deserializzaDatiV2(rpt));
		return response;
	}

	/** Stesso principio di {@link PaGetPaymentActivationService#acquisisciPendenzaAssente}. */
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

	private static String troncaCodCarrello(String codCarrello) {
		if (codCarrello == null) {
			return null;
		}
		return codCarrello.length() > LUNGHEZZA_MASSIMA_COD_CARRELLO
				? codCarrello.substring(0, LUNGHEZZA_MASSIMA_COD_CARRELLO) : codCarrello;
	}
}
