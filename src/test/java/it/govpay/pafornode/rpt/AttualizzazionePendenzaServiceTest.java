package it.govpay.pafornode.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.ec.client.beans.NuovaPendenza;
import it.govpay.ec.client.beans.NuovaVocePendenza;
import it.govpay.ec.client.beans.PendenzaVerificata;
import it.govpay.ec.client.beans.StatoPendenzaVerificata;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.send.SendClient;
import it.govpay.pafornode.verifica.PendenzaVerificataMapper;
import it.govpay.pafornode.verifica.VerificaClient;
import it.govpay.pendenze.comando.AggiornamentoPendenza;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

class AttualizzazionePendenzaServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneId.of("UTC"));
	private static final String COD_DOMINIO = "12345678901";

	private final SendClient sendClient = mock(SendClient.class);
	private final VerificaClient verificaClient = mock(VerificaClient.class);
	private final PendenzaVerificataMapper pendenzaVerificataMapper = mock(PendenzaVerificataMapper.class);
	private final PosizioneDebitoriaService posizioneDebitoriaService = mock(PosizioneDebitoriaService.class);
	private final ApplicazioneRepository applicazioneRepository = mock(ApplicazioneRepository.class);

	private AttualizzazionePendenzaService service(Duration retention, boolean mandatoria) {
		return new AttualizzazionePendenzaService(sendClient, verificaClient, pendenzaVerificataMapper,
				posizioneDebitoriaService, applicazioneRepository, CLOCK, retention, mandatoria);
	}

	private Pendenza pendenza(OffsetDateTime dataValidita, boolean sendAbilitato, OffsetDateTime sendDataAggiornamento) {
		Pendenza pendenza = new Pendenza();
		pendenza.setIdApplicazione(7L);
		pendenza.setIdPendenza("pend-test");
		pendenza.setNumeroAvviso("302000100000019421");
		pendenza.setDataValidita(dataValidita);
		pendenza.setSendAbilitato(sendAbilitato);
		pendenza.setSendDataAggiornamento(sendDataAggiornamento);
		return pendenza;
	}

	private DominioEntity dominio(String codConnettoreSend) {
		DominioEntity dominio = new DominioEntity();
		dominio.setId(1L);
		dominio.setCodDominio(COD_DOMINIO);
		dominio.setCodConnettoreSend(codConnettoreSend);
		return dominio;
	}

	private void applicazionePersistita(String codConnettoreIntegrazione) {
		when(applicazioneRepository.findById(7L)).thenReturn(Optional.of(ApplicazioneEntity.builder()
				.codApplicazione("APP-TEST").codConnettoreIntegrazione(codConnettoreIntegrazione).build()));
	}

	private PendenzaVerificata pendenzaVerificata(StatoPendenzaVerificata stato) {
		return pendenzaVerificata(stato, COD_DOMINIO, "302000100000019421");
	}

	private PendenzaVerificata pendenzaVerificata(StatoPendenzaVerificata stato, String idDominio,
			String numeroAvviso) {
		PendenzaVerificata risposta = new PendenzaVerificata();
		risposta.setStato(stato);
		if (stato == StatoPendenzaVerificata.NON_ESEGUITA) {
			NuovaVocePendenza voce = new NuovaVocePendenza();
			voce.setIdVocePendenza("voce-1");
			voce.setImporto(BigDecimal.TEN);
			voce.setDescrizione("voce riacquisita");
			voce.setCodEntrata("TARI");
			NuovaPendenza pendenza = new NuovaPendenza();
			pendenza.setImporto(BigDecimal.TEN);
			pendenza.setCausale("causale riacquisita");
			pendenza.setIdDominio(idDominio);
			pendenza.setNumeroAvviso(numeroAvviso);
			pendenza.setVoci(List.of(voce));
			risposta.setPendenza(pendenza);
		}
		return risposta;
	}

	@Test
	void nonFaNullaSeLaValiditaNonEScaduta() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).plusDays(1), true, null);

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio("SEND_TEST"), COD_DOMINIO);

		verifyNoInteractions(sendClient, verificaClient, applicazioneRepository);
		assertThat(pendenza.getSendImportoTotale()).isNull();
	}

	@Test
	void attualizzaLimportoSendSeAbilitatoEConnettoreConfiguratoSenzaConnettoreDiVerifica() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), true, null);
		applicazionePersistita(null);
		when(sendClient.recuperaImportoNotifica("SEND_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(256L);

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio("SEND_TEST"), COD_DOMINIO);

		verify(posizioneDebitoriaService).registraImportoSend("APP-TEST", "pend-test", 2.56, OffsetDateTime.now(CLOCK));
		verifyNoInteractions(verificaClient);
	}

	@Test
	void nonRichiamaSendSeDentroLaRetention() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), true,
				OffsetDateTime.now(CLOCK).minusMinutes(10));
		applicazionePersistita(null);

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio("SEND_TEST"), COD_DOMINIO);

		verify(sendClient, never()).recuperaImportoNotifica(any(), any(), any());
	}

	@Test
	void richiamaSendSeLaRetentionEScaduta() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), true,
				OffsetDateTime.now(CLOCK).minusHours(2));
		applicazionePersistita(null);
		when(sendClient.recuperaImportoNotifica("SEND_TEST", COD_DOMINIO, pendenza.getNumeroAvviso())).thenReturn(0L);

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio("SEND_TEST"), COD_DOMINIO);

		verify(sendClient).recuperaImportoNotifica("SEND_TEST", COD_DOMINIO, pendenza.getNumeroAvviso());
	}

	@Test
	void nonFaNullaSeNeSendNeConnettoreDiVerificaSonoDisponibiliENonEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita(null);

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio(null), COD_DOMINIO);

		verify(sendClient, never()).recuperaImportoNotifica(any(), any(), any());
	}

	@Test
	void falliceConPagamentoScadutoSeMandatoriaENessunaAttualizzazionePossibile() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita(null);

		assertThatThrownBy(() -> service(Duration.ofHours(1), true).attualizza(pendenza, dominio(null), COD_DOMINIO))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCADUTO);
	}

	@Test
	void riacquisisceLaPendenzaSeIlConnettoreDiVerificaEConfigurato() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		PendenzaVerificata risposta = pendenzaVerificata(StatoPendenzaVerificata.NON_ESEGUITA);
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso())).thenReturn(risposta);
		AggiornamentoPendenza comando = new AggiornamentoPendenza("pend-test", 1L, 1L, "codifica", 10.0,
				"302000100000019421", null, null, List.of(), "causale riacquisita");
		when(pendenzaVerificataMapper.toAggiornamentoPendenza(pendenza, risposta.getPendenza(), 1L))
				.thenReturn(comando);

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio(null), COD_DOMINIO);

		verify(posizioneDebitoriaService).aggiornaPendenza("APP-TEST", "pend-test", comando);
	}

	@Test
	void falliceConPagamentoAnnullatoSeLaVerificaRestituisceAnnullata() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(pendenzaVerificata(StatoPendenzaVerificata.ANNULLATA));

		assertThatThrownBy(
				() -> service(Duration.ofHours(1), false).attualizza(pendenza, dominio(null), COD_DOMINIO))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_ANNULLATO);
	}

	@Test
	void falliceConPagamentoScadutoSeLaVerificaRestituisceScaduta() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(pendenzaVerificata(StatoPendenzaVerificata.SCADUTA));

		assertThatThrownBy(
				() -> service(Duration.ofHours(1), false).attualizza(pendenza, dominio(null), COD_DOMINIO))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCADUTO);
	}

	/**
	 * A differenza di {@code ANNULLATA}/{@code SCADUTA} (esiti definitivi, sempre
	 * bloccanti): {@code SCONOSCIUTA} e' un'incertezza dell'ente, non un esito — stesso
	 * trattamento del connettore non raggiungibile, si procede con i dati esistenti se
	 * l'attualizzazione non e' mandatoria.
	 */
	@Test
	void nonFalliceSeLaVerificaRestituisceSconosciutaENonEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(pendenzaVerificata(StatoPendenzaVerificata.SCONOSCIUTA));

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio(null), COD_DOMINIO);

		verify(posizioneDebitoriaService, never()).aggiornaPendenza(any(), any(), any());
	}

	@Test
	void falliceConPagamentoScadutoSeLaVerificaRestituisceSconosciutaEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(pendenzaVerificata(StatoPendenzaVerificata.SCONOSCIUTA));

		assertThatThrownBy(
				() -> service(Duration.ofHours(1), true).attualizza(pendenza, dominio(null), COD_DOMINIO))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCADUTO);
	}

	/**
	 * Il fallimento della Verifica mandatoria va controllato PRIMA di procedere a SEND:
	 * un esito positivo di SEND (connettore configurato e abilitato, separato dalla
	 * Verifica) non deve mascherare il fallimento della Verifica stessa, quando il suo
	 * connettore e' configurato (quindi tentato).
	 */
	@Test
	void falliceConPagamentoScadutoSeLaVerificaFalliscEMandatoriaAncheSeSendRiuscirebbe() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), true, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(pendenzaVerificata(StatoPendenzaVerificata.SCONOSCIUTA));
		when(sendClient.recuperaImportoNotifica("SEND_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(100L);

		assertThatThrownBy(() -> service(Duration.ofHours(1), true).attualizza(pendenza, dominio("SEND_TEST"),
				COD_DOMINIO)).isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCADUTO);
		verifyNoInteractions(sendClient);
		verify(posizioneDebitoriaService, never()).registraImportoSend(any(), any(), any(), any());
	}

	@Test
	void nonFalliceSeLaMappaturaDellaRispostaFallisceENonEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		PendenzaVerificata risposta = pendenzaVerificata(StatoPendenzaVerificata.NON_ESEGUITA);
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso())).thenReturn(risposta);
		when(pendenzaVerificataMapper.toAggiornamentoPendenza(pendenza, risposta.getPendenza(), 1L))
				.thenThrow(new IllegalStateException("voce non riconosciuta"));

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio(null), COD_DOMINIO);

		verify(posizioneDebitoriaService, never()).aggiornaPendenza(any(), any(), any());
	}

	@Test
	void falliceConPagamentoScadutoSeLaMappaturaDellaRispostaFalliscEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		PendenzaVerificata risposta = pendenzaVerificata(StatoPendenzaVerificata.NON_ESEGUITA);
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso())).thenReturn(risposta);
		when(pendenzaVerificataMapper.toAggiornamentoPendenza(pendenza, risposta.getPendenza(), 1L))
				.thenThrow(new IllegalStateException("voce non riconosciuta"));

		assertThatThrownBy(
				() -> service(Duration.ofHours(1), true).attualizza(pendenza, dominio(null), COD_DOMINIO))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCADUTO);
	}

	@Test
	void riacquisisceEPoiAttualizzaAncheLimportoSendSeEntrambiDisponibili() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), true, null);
		applicazionePersistita("VERIFICA_TEST");
		PendenzaVerificata risposta = pendenzaVerificata(StatoPendenzaVerificata.NON_ESEGUITA);
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso())).thenReturn(risposta);
		when(pendenzaVerificataMapper.toAggiornamentoPendenza(pendenza, risposta.getPendenza(), 1L)).thenReturn(
				new AggiornamentoPendenza("pend-test", 1L, 1L, "codifica", 10.0, "302000100000019421", null, null,
						List.of(), "causale"));
		when(sendClient.recuperaImportoNotifica("SEND_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(100L);

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio("SEND_TEST"), COD_DOMINIO);

		verify(posizioneDebitoriaService).aggiornaPendenza(any(), any(), any());
		verify(posizioneDebitoriaService).registraImportoSend("APP-TEST", "pend-test", 1.0, OffsetDateTime.now(CLOCK));
	}

	/**
	 * Stesso trattamento di {@code SCONOSCIUTA}: l'identita' non corrispondente non e' un
	 * esito definitivo dell'ente, solo un dato non utilizzabile — si procede con quelli
	 * esistenti se non mandatoria, mai si aggiorna la pendenza con una risposta scorrelata.
	 */
	@Test
	void nonFalliceSeLaVerificaRestituisceUnDominioDiversoENonEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(pendenzaVerificata(StatoPendenzaVerificata.NON_ESEGUITA, "99999999999",
						pendenza.getNumeroAvviso()));

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio(null), COD_DOMINIO);

		verify(posizioneDebitoriaService, never()).aggiornaPendenza(any(), any(), any());
	}

	@Test
	void falliceConPagamentoScadutoSeLaVerificaRestituisceUnDominioDiversoEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(pendenzaVerificata(StatoPendenzaVerificata.NON_ESEGUITA, "99999999999",
						pendenza.getNumeroAvviso()));

		assertThatThrownBy(
				() -> service(Duration.ofHours(1), true).attualizza(pendenza, dominio(null), COD_DOMINIO))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCADUTO);
	}

	@Test
	void nonFalliceSeLaVerificaRestituisceUnNumeroAvvisoDiversoENonEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenReturn(pendenzaVerificata(StatoPendenzaVerificata.NON_ESEGUITA, COD_DOMINIO,
						"999999999999999999"));

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio(null), COD_DOMINIO);

		verify(posizioneDebitoriaService, never()).aggiornaPendenza(any(), any(), any());
	}

	@Test
	void nonFaNullaSeLaPendenzaNonENonEseguita() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), true, null);
		pendenza.setStato(it.govpay.pendenze.model.StatoPendenza.ESEGUITO);
		applicazionePersistita("VERIFICA_TEST");

		service(Duration.ofHours(1), true).attualizza(pendenza, dominio("SEND_TEST"), COD_DOMINIO);

		verifyNoInteractions(verificaClient, sendClient);
	}

	@Test
	void nonFaNullaSeLavvisoEGiaScaduto() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), true, null);
		pendenza.setDataScadenzaAvviso(OffsetDateTime.now(CLOCK).minusDays(1).toLocalDate().atStartOfDay(CLOCK.getZone())
				.toOffsetDateTime());
		applicazionePersistita("VERIFICA_TEST");

		service(Duration.ofHours(1), true).attualizza(pendenza, dominio("SEND_TEST"), COD_DOMINIO);

		verifyNoInteractions(verificaClient, sendClient);
	}

	/**
	 * {@code dataValidita} e' sempre scritta a mezzanotte dal mapper: una validita' odierna
	 * non deve gia' innescare l'attualizzazione (resta valida fino a fine giornata, stesso
	 * confronto per giorno di {@code PaForNodeChecksTest#procedeSeLavvisoScadeOggi}).
	 */
	@Test
	void nonAttualizzaSeLaValiditaEOggi() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).toLocalDate().atStartOfDay(CLOCK.getZone())
				.toOffsetDateTime(), true, null);
		applicazionePersistita("VERIFICA_TEST");

		service(Duration.ofHours(1), true).attualizza(pendenza, dominio("SEND_TEST"), COD_DOMINIO);

		verifyNoInteractions(verificaClient, sendClient);
	}

	@Test
	void nonFalliceSeIlConnettoreDiVerificaNonERaggiungibileENonEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenThrow(new org.springframework.web.client.ResourceAccessException("connessione rifiutata"));

		service(Duration.ofHours(1), false).attualizza(pendenza, dominio(null), COD_DOMINIO);

		verify(posizioneDebitoriaService, never()).aggiornaPendenza(any(), any(), any());
	}

	@Test
	void falliceConPagamentoScadutoSeIlConnettoreDiVerificaNonERaggiungibileEMandatoria() {
		Pendenza pendenza = pendenza(OffsetDateTime.now(CLOCK).minusDays(1), false, null);
		applicazionePersistita("VERIFICA_TEST");
		when(verificaClient.getAvviso("VERIFICA_TEST", COD_DOMINIO, pendenza.getNumeroAvviso()))
				.thenThrow(new org.springframework.web.client.ResourceAccessException("connessione rifiutata"));

		assertThatThrownBy(
				() -> service(Duration.ofHours(1), true).attualizza(pendenza, dominio(null), COD_DOMINIO))
				.isInstanceOf(NdpException.class)
				.extracting(e -> ((NdpException) e).getFault()).isEqualTo(FaultPa.PAA_PAGAMENTO_SCADUTO);
	}
}
