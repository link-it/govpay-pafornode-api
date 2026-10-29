package it.govpay.pafornode.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
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
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeRes;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IntermediarioRepository;
import it.govpay.common.repository.StazioneRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.rpt.PaGetPaymentActivationService;
import it.govpay.pafornode.rpt.PaGetPaymentV2ActivationService;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;

/**
 * Unit test diretto sull'endpoint (nessun contesto Spring, nessuna chiamata HTTP): verifica
 * che il principal autenticato (seminato a mano in {@code SecurityContextHolder}, nello stesso
 * thread della chiamata al metodo — non serve la catena di sicurezza reale) sia confrontato con
 * l'intermediario dichiarato in {@code idBrokerPA}, come nel legacy
 * ({@code AuthorizationManager.checkPrincipal}), incluso il rifiuto esplicito quando
 * l'autenticazione e' del tutto assente.
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
	private final PaGetPaymentActivationService paGetPaymentActivationService = mock(
			PaGetPaymentActivationService.class);
	private final PaGetPaymentV2ActivationService paGetPaymentV2ActivationService = mock(
			PaGetPaymentV2ActivationService.class);

	private final PaForNodeEndpoint endpoint = new PaForNodeEndpoint(dominioRepository, intermediarioRepository,
			stazioneRepository, unitaOperativaRepository, pendenzaRepository, pagamentoRepository,
			paGetPaymentActivationService, paGetPaymentV2ActivationService, Clock.systemDefaultZone(), List.of());

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

		PaVerifyPaymentNoticeRes response = endpoint.paVerifyPaymentNotice(richiesta()).getValue();

		assertThat(response.getOutcome()).isEqualTo(StOutcome.KO);
		assertThat(response.getFault().getFaultCode()).isEqualTo(FaultPa.PAA_SYSTEM_ERROR.name());
		assertThat(response.getFault().getDescription()).contains("Autorizzazione fallita").contains(ID_BROKER_PA);
	}

	@Test
	void rifiutaSeNessunaAutenticazionePresente() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario(ID_BROKER_PA)
				.principal(PRINCIPAL_REGISTRATO).abilitato(true).build();
		when(intermediarioRepository.findByCodIntermediario(ID_BROKER_PA)).thenReturn(Optional.of(intermediario));
		// Nessuna chiamata ad autenticaCome(...): SecurityContextHolder resta vuoto.

		PaVerifyPaymentNoticeRes response = endpoint.paVerifyPaymentNotice(richiesta()).getValue();

		assertThat(response.getOutcome()).isEqualTo(StOutcome.KO);
		assertThat(response.getFault().getFaultCode()).isEqualTo(FaultPa.PAA_SYSTEM_ERROR.name());
	}

	@Test
	void procedeOltreIlControlloSeIlPrincipalCorrisponde() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario(ID_BROKER_PA)
				.principal(PRINCIPAL_REGISTRATO).abilitato(true).build();
		when(intermediarioRepository.findByCodIntermediario(ID_BROKER_PA)).thenReturn(Optional.of(intermediario));
		when(stazioneRepository.findByIntermediarioCodIntermediarioAndCodStazione(any(), any()))
				.thenReturn(Optional.empty());
		autenticaCome(PRINCIPAL_REGISTRATO);

		PaVerifyPaymentNoticeRes response = endpoint.paVerifyPaymentNotice(richiesta()).getValue();

		// Non piu' PAA_SYSTEM_ERROR (autorizzazione superata): il prossimo controllo in
		// sequenza (stazione non trovata) e' quello che fallisce.
		assertThat(response.getFault().getFaultCode()).isEqualTo(FaultPa.PAA_STAZIONE_INT_ERRATA.name());
	}
}
