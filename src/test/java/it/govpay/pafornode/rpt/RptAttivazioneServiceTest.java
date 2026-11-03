package it.govpay.pafornode.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.pafornode.Application;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class RptAttivazioneServiceTest {

	@Autowired
	private RptAttivazioneService service;

	@Autowired
	private PosizioneDebitoriaService posizioneDebitoriaService;

	private Pendenza pendenzaPersistita(String iuv) {
		PosizioneDebitoria posizione = new PosizioneDebitoria();
		posizione.setIdApplicazione(1L);
		posizione.setCodDocumento("doc-" + iuv);
		posizione.setIdDominio(1L);
		posizione.setDescrizione("Descrizione della posizione di test");

		SoggettoDebitore soggetto = new SoggettoDebitore();
		soggetto.setTipo(TipoSoggetto.F);
		soggetto.setIdentificativo("RSSMRA80A01H501U");
		soggetto.setAnagrafica("Mario Rossi");
		posizione.addSoggettoDebitore(soggetto);

		OpzionePagamento opzione = new OpzionePagamento();
		opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
		posizione.addOpzionePagamento(opzione);

		Pendenza pendenza = new Pendenza();
		pendenza.setIdApplicazione(1L);
		pendenza.setIdPendenza("pend-" + iuv);
		pendenza.setIdTipoPendenza(1L);
		pendenza.setIdTipoVersamento(1L);
		pendenza.setImporto(10.0);
		pendenza.setIuv(iuv);
		pendenza.setSrcIuv(iuv);
		pendenza.setNumeroAvviso("3" + iuv);
		pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
		pendenza.setDebitoreAnagrafica("Mario Rossi");
		pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
		pendenza.setStato(it.govpay.pendenze.model.StatoPendenza.NON_ESEGUITO);
		opzione.addPendenza(pendenza);

		VocePendenza voce = new VocePendenza();
		voce.setIdVocePendenza("voce-" + iuv);
		voce.setImporto(10.0);
		voce.setDescrizione("test");
		voce.setIndice(1);
		voce.setStato(it.govpay.pendenze.model.StatoVocePendenza.NON_ESEGUITO);
		voce.setIdTributo(1L);
		pendenza.addVocePendenza(voce);

		PosizioneDebitoria persistita = posizioneDebitoriaService.crea(DatiPosizioneDebitoria.da(posizione));
		return persistita.getOpzioniPagamento().get(0).getPendenze().get(0);
	}

	private DominioEntity dominio() {
		DominioEntity dominio = new DominioEntity();
		dominio.setId(1L);
		dominio.setCodDominio("12345678901");
		return dominio;
	}

	private StazioneEntity stazione() {
		StazioneEntity stazione = new StazioneEntity();
		stazione.setCodStazione("STAZIONE-TEST");
		return stazione;
	}

	@Test
	void ricostruisceUnaRptStandinSenzaXmlRpt() {
		Pendenza pendenza = pendenzaPersistita("00000000000000000003");
		DominioEntity dominio = dominio();
		dominio.setStazione(stazione());
		OffsetDateTime dataPagamento = OffsetDateTime.now().minusMinutes(5);

		Rpt rpt = service.ricostruisci(pendenza, dominio, RptAttivazioneService.VERSIONE_SANP_240, "receipt-standin",
				dataPagamento);

		assertThat(rpt.getId()).isNotNull();
		assertThat(rpt.getIdVersamento()).isEqualTo(pendenza.getId());
		assertThat(rpt.getIuv()).isEqualTo(pendenza.getIuv());
		assertThat(rpt.getIur()).isEqualTo("receipt-standin");
		assertThat(rpt.getCodDominio()).isEqualTo(dominio.getCodDominio());
		assertThat(rpt.getCodStazione()).isEqualTo(stazione().getCodStazione());
		assertThat(rpt.getCodMsgRichiesta()).isEqualTo("receipt-standin");
		assertThat(rpt.getVersione()).isEqualTo(RptAttivazioneService.VERSIONE_SANP_240);
		assertThat(rpt.getStato()).isEqualTo(StatoRpt.RPT_ACCETTATA_NODO.name());
		assertThat(rpt.getModelloPagamento()).isEqualTo(RptAttivazioneService.MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP);
		assertThat(rpt.getDataMsgRichiesta()).isEqualTo(dataPagamento);
		assertThat(rpt.getXmlRpt()).isNull();
		assertThat(rpt.getCodCarrello()).isNull();
	}

	@Test
	void ricostruisceConDataMsgRichiestaAdessoSeDataPagamentoAssente() {
		Pendenza pendenza = pendenzaPersistita("00000000000000000004");
		DominioEntity dominio = dominio();
		dominio.setStazione(stazione());

		Rpt rpt = service.ricostruisci(pendenza, dominio, RptAttivazioneService.VERSIONE_SANP_321_V2,
				"receipt-standin-2", null);

		assertThat(rpt.getDataMsgRichiesta()).isNotNull();
	}

	@Test
	void rifiutaSeIlDominioNonHaUnaStazioneDiDefault() {
		Pendenza pendenza = pendenzaPersistita("00000000000000000005");
		DominioEntity dominio = dominio();

		assertThatThrownBy(() -> service.ricostruisci(pendenza, dominio, RptAttivazioneService.VERSIONE_SANP_240,
				"receipt-standin-3", null)).isInstanceOf(IllegalStateException.class);
	}
}
