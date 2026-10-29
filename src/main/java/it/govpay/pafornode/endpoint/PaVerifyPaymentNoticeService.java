package it.govpay.pafornode.endpoint;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import javax.xml.datatype.XMLGregorianCalendar;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentOptionDescriptionPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentOptionsDescriptionListPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.StAmountOption;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.ec.client.beans.PendenzaVerificata;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.rpt.AttualizzazionePendenzaService;
import it.govpay.pafornode.rpt.UnitaOperativaResolver;
import it.govpay.pafornode.rpt.XmlDateUtils;
import it.govpay.pafornode.verifica.ApplicazioneDominioResolver;
import it.govpay.pafornode.verifica.PendenzaVerificataMapper;
import it.govpay.pafornode.verifica.VerificaClient;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.service.CausaleVersamentoCodec;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * Intera operazione {@code paVerifyPaymentNotice}: verifica (ed eventuale attualizzazione
 * scrivente), in un'unica transazione gestita qui — non nell'endpoint, che resta non
 * transazionale e si limita a chiamare questo servizio dentro il proprio try/catch, stesso
 * principio di {@code PaGetPaymentActivationService} (§3quinquies/punto aperto #9 del design
 * doc, chiuso qui 2026-10-29).
 *
 * <p><b>Perche' questa estrazione era necessaria</b>: prima, {@code paVerifyPaymentNotice}
 * restava {@code @Transactional(readOnly = true)} nell'endpoint, incompatibile con le
 * scritture dell'attualizzazione (SEND/riacquisizione/acquisizione, tutte ora cablate anche
 * qui) — una connessione JDBC in sola lettura avrebbe potuto far fallire quelle scritture in
 * produzione su PostgreSQL. Qui il metodo e' scrivente a tutti gli effetti.
 *
 * <p>A differenza di {@code PaGetPaymentActivationService}, nessun lock pessimistico
 * esplicito sulla pendenza: questa operazione non attiva/annulla nessuna RPT (il legacy non
 * ne acquisisce uno nemmeno per {@code paVerifyPaymentNotice}), solo eventuali scritture di
 * attualizzazione gia' gestite dai rispettivi servizi.
 */
@Service
public class PaVerifyPaymentNoticeService {

	private final DominioRepository dominioRepository;
	private final IntermediarioRepository intermediarioRepository;
	private final StazioneRepository stazioneRepository;
	private final UnitaOperativaRepository unitaOperativaRepository;
	private final PendenzaRepository pendenzaRepository;
	private final PagamentoRepository pagamentoRepository;
	private final AttualizzazionePendenzaService attualizzazionePendenzaService;
	private final ApplicazioneDominioResolver applicazioneDominioResolver;
	private final VerificaClient verificaClient;
	private final PendenzaVerificataMapper pendenzaVerificataMapper;
	private final PosizioneDebitoriaService posizioneDebitoriaService;
	private final Clock clock;
	private final List<String> navSondaPagoPA;

	public PaVerifyPaymentNoticeService(DominioRepository dominioRepository,
			IntermediarioRepository intermediarioRepository, StazioneRepository stazioneRepository,
			UnitaOperativaRepository unitaOperativaRepository, PendenzaRepository pendenzaRepository,
			PagamentoRepository pagamentoRepository, AttualizzazionePendenzaService attualizzazionePendenzaService,
			ApplicazioneDominioResolver applicazioneDominioResolver, VerificaClient verificaClient,
			PendenzaVerificataMapper pendenzaVerificataMapper, PosizioneDebitoriaService posizioneDebitoriaService,
			Clock clock, @Value("#{'${govpay.pafornode.sonda-nav:}'.split(',')}") List<String> navSondaPagoPA) {
		this.dominioRepository = dominioRepository;
		this.intermediarioRepository = intermediarioRepository;
		this.stazioneRepository = stazioneRepository;
		this.unitaOperativaRepository = unitaOperativaRepository;
		this.pendenzaRepository = pendenzaRepository;
		this.pagamentoRepository = pagamentoRepository;
		this.attualizzazionePendenzaService = attualizzazionePendenzaService;
		this.applicazioneDominioResolver = applicazioneDominioResolver;
		this.verificaClient = verificaClient;
		this.pendenzaVerificataMapper = pendenzaVerificataMapper;
		this.posizioneDebitoriaService = posizioneDebitoriaService;
		this.clock = clock;
		this.navSondaPagoPA = navSondaPagoPA;
	}

	@Transactional
	public PaVerifyPaymentNoticeRes eseguiVerifica(PaVerifyPaymentNoticeReq request, String numeroAvviso,
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

		Optional<Pendenza> pendenzaEsistente = pendenzaRepository.findByIdDominioAndIuv(dominio.getId(), iuv);
		Pendenza pendenza;
		if (pendenzaEsistente.isPresent()) {
			pendenza = pendenzaEsistente.get();
			attualizzazionePendenzaService.attualizza(pendenza, dominio, codDominio);
		} else {
			pendenza = acquisisciPendenzaAssente(dominio, iuv, numeroAvviso, codDominio);
		}

		PaForNodeChecks.verificaStatoPagabile(pendenza, codDominio, clock, pagamentoRepository);

		return buildRispostaPositiva(pendenza, dominio);
	}

	/** Stesso principio di {@code PaGetPaymentActivationService#acquisisciPendenzaAssente}. */
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
}
