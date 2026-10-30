package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtQrCode;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeReq;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.rpt.AttualizzazionePendenzaService;
import it.govpay.pafornode.verifica.ApplicazioneDominioResolver;
import it.govpay.pafornode.verifica.PendenzaVerificataMapper;
import it.govpay.pafornode.verifica.VerificaClient;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * Unit test diretto su {@link PaVerifyPaymentNoticeService} (nessun contesto Spring, nessuna
 * chiamata HTTP): verifica che il principal autenticato (seminato a mano in {@code
 * SecurityContextHolder}, nello stesso thread della chiamata al metodo — non serve la catena
 * di sicurezza reale) sia confrontato con l'intermediario dichiarato in {@code idBrokerPA},
 * come nel legacy ({@code AuthorizationManager.checkPrincipal}), incluso il rifiuto esplicito
 * quando l'autenticazione e' del tutto assente.
 *
 * <p>Complementare a {@code PaForNodeEndpointTest} (integrazione via HTTP, catena di
 * sicurezza realmente attiva con il metodo HEADER): quel test esercita il confronto end-to-end
 * con un'identita' autenticata per davvero, qui invece si isolano anche i casi di rifiuto
 * (principal che non corrisponde, autenticazione assente) senza dover passare per HTTP.
 */
class PaForNodeEndpointAutorizzazioneTest {

	private static final String ID_BROKER_PA = "BROKER-TEST";
	private static final String PRINCIPAL_REGISTRATO = "CN=broker-test.example.it";

	private final DominioRepository dominioRepository = mock(DominioRepository.class);
	private final IntermediarioRepository intermediarioRepository = mock(IntermediarioRepository.class);
	private final StazioneRepository stazioneRepository = mock(StazioneRepository.class);
	private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);
	private final PendenzaRepository pendenzaRepository = mock(PendenzaRepository.class);
	private final PagamentoRepository pagamentoRepository = mock(PagamentoRepository.class);
	private final AttualizzazionePendenzaService attualizzazionePendenzaService = mock(
			AttualizzazionePendenzaService.class);
	private final ApplicazioneDominioResolver applicazioneDominioResolver = mock(ApplicazioneDominioResolver.class);
	private final VerificaClient verificaClient = mock(VerificaClient.class);
	private final PendenzaVerificataMapper pendenzaVerificataMapper = mock(PendenzaVerificataMapper.class);
	private final PosizioneDebitoriaService posizioneDebitoriaService = mock(PosizioneDebitoriaService.class);

	private final PaVerifyPaymentNoticeService service = new PaVerifyPaymentNoticeService(dominioRepository,
			intermediarioRepository, stazioneRepository, unitaOperativaRepository, pendenzaRepository,
			pagamentoRepository, attualizzazionePendenzaService, applicazioneDominioResolver, verificaClient,
			pendenzaVerificataMapper, posizioneDebitoriaService, Clock.systemDefaultZone(), List.of());

	@AfterEach
	void pulisciContestoSicurezza() {
		SecurityContextHolder.clearContext();
	}

	private PaVerifyPaymentNoticeReq richiesta() {
		CtQrCode qrCode = new CtQrCode();
		qrCode.setFiscalCode("12345678901");
		qrCode.setNoticeNumber("100000000000000001");

		PaVerifyPaymentNoticeReq request = new PaVerifyPaymentNoticeReq();
		request.setIdPA("12345678901");
		request.setIdBrokerPA(ID_BROKER_PA);
		request.setIdStation("STAZIONE-TEST");
		request.setQrCode(qrCode);
		return request;
	}

	private void autenticaCome(String principal) {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null));
	}

	@Test
	void rifiutaSePrincipalAutenticatoNonCorrispondeAllIntermediarioDichiarato() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario(ID_BROKER_PA)
				.principal(PRINCIPAL_REGISTRATO).abilitato(true).build();
		when(intermediarioRepository.findByCodIntermediario(ID_BROKER_PA)).thenReturn(Optional.of(intermediario));
		autenticaCome("CN=broker-altro.example.it");

		assertThatThrownBy(() -> service.eseguiVerifica(richiesta(), "100000000000000001", "12345678901"))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_SYSTEM_ERROR);
	}

	@Test
	void rifiutaSeNessunaAutenticazionePresente() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario(ID_BROKER_PA)
				.principal(PRINCIPAL_REGISTRATO).abilitato(true).build();
		when(intermediarioRepository.findByCodIntermediario(ID_BROKER_PA)).thenReturn(Optional.of(intermediario));
		// Nessuna chiamata ad autenticaCome(...): SecurityContextHolder resta vuoto.

		assertThatThrownBy(() -> service.eseguiVerifica(richiesta(), "100000000000000001", "12345678901"))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_SYSTEM_ERROR);
	}

	@Test
	void procedeOltreIlControlloSeIlPrincipalCorrisponde() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario(ID_BROKER_PA)
				.principal(PRINCIPAL_REGISTRATO).abilitato(true).build();
		when(intermediarioRepository.findByCodIntermediario(ID_BROKER_PA)).thenReturn(Optional.of(intermediario));
		when(stazioneRepository.findByIntermediarioCodIntermediarioAndCodStazione(any(), any()))
				.thenReturn(Optional.empty());
		autenticaCome(PRINCIPAL_REGISTRATO);

		// Non piu' PAA_SYSTEM_ERROR (autorizzazione superata): il prossimo controllo in
		// sequenza (stazione non trovata) e' quello che fallisce.
		assertThatThrownBy(() -> service.eseguiVerifica(richiesta(), "100000000000000001", "12345678901"))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_STAZIONE_INT_ERRATA);
	}
}
