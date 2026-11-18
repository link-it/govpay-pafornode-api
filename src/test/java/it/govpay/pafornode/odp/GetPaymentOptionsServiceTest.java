package it.govpay.pafornode.odp;

import it.govpay.pafornode.PosizioniDiTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.common.utils.IuvUtils;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pafornode.odp.api.model.EnumPo;
import it.govpay.pafornode.odp.api.model.PaymentOptionsResponse;
import it.govpay.pendenze.entity.Debitore;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.PdDebitore;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.PendenzaRepository;

/**
 * Unit test diretto su {@link GetPaymentOptionsService} (nessun contesto Spring/HTTP) — stesso
 * principio di {@code PaSendRTServiceTest}: isola i rifiuti precoci e il filtro delle opzioni
 * sorelle senza dover passare per un contesto REST completo.
 */
class GetPaymentOptionsServiceTest {

	private static final String COD_DOMINIO = "12345678901";
	private static final String PRINCIPAL_REGISTRATO = "CN=broker-test.example.it";
	private static final IuvUtils.IdentificativiPagamento IDENTIFICATIVI = IuvUtils.genera(1, "", null, null, 1L);
	private static final String NUMERO_AVVISO = IDENTIFICATIVI.numeroAvviso();

	private final DominioRepository dominioRepository = mock(DominioRepository.class);
	private final PendenzaRepository pendenzaRepository = mock(PendenzaRepository.class);
	private final PagamentoRepository pagamentoRepository = mock(PagamentoRepository.class);
	private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);

	private final GetPaymentOptionsService service = new GetPaymentOptionsService(dominioRepository,
			pendenzaRepository, pagamentoRepository, unitaOperativaRepository, Clock.systemDefaultZone());

	@AfterEach
	void pulisciContestoSicurezza() {
		SecurityContextHolder.clearContext();
	}

	private void autenticaCome(String principal) {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null));
	}

	private DominioEntity dominioConStazioneEIntermediario() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario("BROKER-TEST")
				.principal(PRINCIPAL_REGISTRATO).abilitato(true).build();
		StazioneEntity stazione = StazioneEntity.builder().codStazione("STAZIONE-TEST").intermediario(intermediario)
				.build();
		DominioEntity dominio = DominioEntity.builder().codDominio(COD_DOMINIO).ragioneSociale("Comune di Test")
				.auxDigit(1).stazione(stazione).build();
		dominio.setId(1L);
		return dominio;
	}

	private Pendenza pendenza(StatoPendenza stato, double importo) {
		Pendenza pendenza = new Pendenza();
		pendenza.setIuv(IDENTIFICATIVI.iuv());
		pendenza.setNumeroAvviso(NUMERO_AVVISO);
		pendenza.setImporto(importo);
		pendenza.setStato(stato);
		pendenza.setDataValidita(OffsetDateTime.now().minusDays(10));
		return pendenza;
	}

	@Test
	void rifiutaSeDominioSconosciuto() {
		when(dominioRepository.findByCodDominio(COD_DOMINIO)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.eseguiGetPaymentOptions(COD_DOMINIO, NUMERO_AVVISO))
				.isInstanceOf(NdpException.class).extracting(e -> ((NdpException) e).getFault())
				.isEqualTo(FaultPa.PAA_ID_DOMINIO_ERRATO);
	}

	@Test
	void rifiutaSeStazioneAssente() {
		DominioEntity dominio = DominioEntity.builder().codDominio(COD_DOMINIO).build();
		dominio.setId(1L);
		when(dominioRepository.findByCodDominio(COD_DOMINIO)).thenReturn(Optional.of(dominio));

		assertThatThrownBy(() -> service.eseguiGetPaymentOptions(COD_DOMINIO, NUMERO_AVVISO))
				.isInstanceOf(NdpException.class).extracting(e -> ((NdpException) e).getFault())
				.isEqualTo(FaultPa.PAA_STAZIONE_INT_ERRATA);
	}

	@Test
	void rifiutaSeIntermediarioAssente() {
		StazioneEntity stazione = StazioneEntity.builder().codStazione("STAZIONE-TEST").build();
		DominioEntity dominio = DominioEntity.builder().codDominio(COD_DOMINIO).stazione(stazione).build();
		dominio.setId(1L);
		when(dominioRepository.findByCodDominio(COD_DOMINIO)).thenReturn(Optional.of(dominio));

		assertThatThrownBy(() -> service.eseguiGetPaymentOptions(COD_DOMINIO, NUMERO_AVVISO))
				.isInstanceOf(NdpException.class).extracting(e -> ((NdpException) e).getFault())
				.isEqualTo(FaultPa.PAA_ID_INTERMEDIARIO_ERRATO);
	}

	@Test
	void rifiutaSePrincipalNonCorrispondente() {
		when(dominioRepository.findByCodDominio(COD_DOMINIO)).thenReturn(Optional.of(dominioConStazioneEIntermediario()));
		autenticaCome("CN=qualcun-altro.example.it");

		assertThatThrownBy(() -> service.eseguiGetPaymentOptions(COD_DOMINIO, NUMERO_AVVISO))
				.isInstanceOf(NdpException.class).extracting(e -> ((NdpException) e).getFault())
				.isEqualTo(FaultPa.PAA_SYSTEM_ERROR);
	}

	@Test
	void rifiutaSeNavSconosciuto() {
		when(dominioRepository.findByCodDominio(COD_DOMINIO)).thenReturn(Optional.of(dominioConStazioneEIntermediario()));
		autenticaCome(PRINCIPAL_REGISTRATO);
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.eseguiGetPaymentOptions(COD_DOMINIO, NUMERO_AVVISO))
				.isInstanceOf(NdpException.class).extracting(e -> ((NdpException) e).getFault())
				.isEqualTo(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO);
	}

	@Test
	void rifiutaSePendenzaRichiestaAnnullata() {
		when(dominioRepository.findByCodDominio(COD_DOMINIO)).thenReturn(Optional.of(dominioConStazioneEIntermediario()));
		autenticaCome(PRINCIPAL_REGISTRATO);
		PosizioneDebitoria posizione = new PosizioneDebitoria();
		OpzionePagamento opzione = new OpzionePagamento();
		opzione.setStato(StatoOpzionePagamento.ANNULLATA);
		PosizioniDiTest.aggiungiOpzione(posizione, opzione);
		Pendenza pendenza = pendenza(StatoPendenza.ANNULLATO, 100);
		opzione.addPendenza(pendenza);
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenza));

		assertThatThrownBy(() -> service.eseguiGetPaymentOptions(COD_DOMINIO, NUMERO_AVVISO))
				.isInstanceOf(NdpException.class).extracting(e -> ((NdpException) e).getFault())
				.isEqualTo(FaultPa.PAA_PAGAMENTO_ANNULLATO);
	}

	/**
	 * Tre opzioni sulla stessa posizione (DISPONIBILE, ATTIVATA, ANNULLATA): la risposta deve
	 * contenere solo le prime due, nell'ordine naturale — deciso con l'utente (il NAV serve
	 * solo a individuare la posizione, non a restituire la sola opzione corrispondente).
	 */
	@Test
	void restituisceSoloLeOpzioniDisponibiliOAttivateDellaStessaPosizione() {
		when(dominioRepository.findByCodDominio(COD_DOMINIO)).thenReturn(Optional.of(dominioConStazioneEIntermediario()));
		autenticaCome(PRINCIPAL_REGISTRATO);

		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setDescrizione("Posizione di test");

		OpzionePagamento disponibile = new OpzionePagamento();
		disponibile.setStato(StatoOpzionePagamento.DISPONIBILE);
		PosizioniDiTest.aggiungiOpzione(posizione, disponibile);
		Pendenza pendenzaRichiesta = pendenza(StatoPendenza.NON_ESEGUITO, 100);
		disponibile.addPendenza(pendenzaRichiesta);

		OpzionePagamento attivata = new OpzionePagamento();
		attivata.setStato(StatoOpzionePagamento.ATTIVATA);
		PosizioniDiTest.aggiungiOpzione(posizione, attivata);
		attivata.addPendenza(pendenza(StatoPendenza.ESEGUITO, 50));

		OpzionePagamento annullata = new OpzionePagamento();
		annullata.setStato(StatoOpzionePagamento.ANNULLATA);
		PosizioniDiTest.aggiungiOpzione(posizione, annullata);
		annullata.addPendenza(pendenza(StatoPendenza.ANNULLATO, 30));

		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenzaRichiesta));

		PaymentOptionsResponse response = service.eseguiGetPaymentOptions(COD_DOMINIO, NUMERO_AVVISO);

		assertThat(response.getOrganizationFiscalCode()).isEqualTo(COD_DOMINIO);
		assertThat(response.getPaymentOptions()).hasSize(2);
		assertThat(response.getPaymentOptions()).extracting(po -> po.getStatus())
				.containsExactly(EnumPo.PO_UNPAID, EnumPo.PO_PAID);
	}

	/**
	 * Posizione con due debitori, ciascuno con la propria opzione e il proprio NAV: la risposta
	 * contiene solo l'opzione del debitore a cui appartiene il NAV richiesto.
	 */
	@Test
	void restituisceSoloLeOpzioniDelDebitoreDelNavRichiesto() {
		when(dominioRepository.findByCodDominio(COD_DOMINIO)).thenReturn(Optional.of(dominioConStazioneEIntermediario()));
		autenticaCome(PRINCIPAL_REGISTRATO);

		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setDescrizione("Posizione con due debitori");
		PdDebitore primo = PosizioniDiTest.aggiungiDebitore(posizione, debitore("RSSMRA80A01H501U"));
		PdDebitore secondo = PosizioniDiTest.aggiungiDebitore(posizione, debitore("FRRGNN90C41H501Y"));

		OpzionePagamento opzionePrimo = new OpzionePagamento();
		opzionePrimo.setStato(StatoOpzionePagamento.DISPONIBILE);
		primo.addOpzionePagamento(opzionePrimo);
		Pendenza pendenzaRichiesta = pendenza(StatoPendenza.NON_ESEGUITO, 100);
		opzionePrimo.addPendenza(pendenzaRichiesta);

		OpzionePagamento opzioneSecondo = new OpzionePagamento();
		opzioneSecondo.setStato(StatoOpzionePagamento.DISPONIBILE);
		secondo.addOpzionePagamento(opzioneSecondo);
		opzioneSecondo.addPendenza(pendenza(StatoPendenza.NON_ESEGUITO, 40));

		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenzaRichiesta));

		PaymentOptionsResponse response = service.eseguiGetPaymentOptions(COD_DOMINIO, NUMERO_AVVISO);

		assertThat(response.getPaymentOptions()).hasSize(1);
		assertThat(response.getPaymentOptions().get(0).getAmount()).isEqualTo(10000L);
	}

	private static Debitore debitore(String identificativo) {
		Debitore debitore = new Debitore();
		debitore.setTipo(TipoSoggetto.F);
		debitore.setIdentificativo(identificativo);
		debitore.setAnagrafica("Debitore " + identificativo);
		return debitore;
	}

	@Test
	void rifiutaSeLaPosizioneNonEAncoraPubblicata() {
		when(dominioRepository.findByCodDominio(COD_DOMINIO)).thenReturn(Optional.of(dominioConStazioneEIntermediario()));
		autenticaCome(PRINCIPAL_REGISTRATO);
		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setDataPubblicazione(java.time.LocalDate.now().plusDays(3));
		OpzionePagamento opzione = new OpzionePagamento();
		opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
		PosizioniDiTest.aggiungiOpzione(posizione, opzione);
		Pendenza pendenza = pendenza(StatoPendenza.NON_ESEGUITO, 100);
		opzione.addPendenza(pendenza);
		when(pendenzaRepository.findByIdDominioAndIuv(any(), any())).thenReturn(Optional.of(pendenza));

		assertThatThrownBy(() -> service.eseguiGetPaymentOptions(COD_DOMINIO, NUMERO_AVVISO))
				.isInstanceOf(NdpException.class).extracting(e -> ((NdpException) e).getFault())
				.isEqualTo(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO);
	}
}
