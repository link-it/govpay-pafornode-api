package it.govpay.pafornode.ricevuta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.ConnettoreEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.repository.ConnettoreEntityRepository;
import it.govpay.pendenze.entity.FlussoRendicontazione;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rendicontazione;
import it.govpay.pendenze.entity.Notifica;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoFlussoRendicontazione;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoPagamentoRicevuta;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoRendicontazione;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoNotifica;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.NotificaRepository;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.RendicontazioneRepository;

/**
 * Verifica {@link AcquisizioneRicevutaService} sul percorso principale (RPT trovata,
 * ricevuta valida) — vedi Javadoc di classe del servizio per i casi esplicitamente non
 * implementati (standin, validazione semantica completa, entrambi responsabilita' del
 * chiamante/fuori perimetro).
 *
 * <p>Contesto Spring completo ({@code Application}), come {@code PaForNodeEndpointTest} —
 * non uno slice {@code @DataJpaTest}: {@code Application} definisce gia' il proprio
 * {@code @EntityScan}/{@code @EnableJpaRepositories} (vedi Javadoc di classe), che un
 * secondo set di annotazioni dello slice duplicherebbe facendo fallire l'avvio del
 * contesto (bean repository registrato due volte). {@code @Transactional} di classe —
 * non una chiamata HTTP come {@code PaForNodeEndpointTest}: ogni test gira nella stessa
 * transazione gestita dal test runner, con rollback automatico al termine.</p>
 *
 * <p>{@code webEnvironment} predefinito (MOCK), non {@code NONE}: la catena di sicurezza
 * di govpay-common-auth (sempre attiva in questo modulo, vedi Javadoc di {@code
 * Application}) richiede un {@code HttpSecurity}/{@code WebApplicationContext} per
 * costruire il proprio filtro — assente con {@code NONE}, che farebbe fallire l'avvio del
 * contesto. Nessun server reale ne' porta random servono qui (nessuna chiamata HTTP),
 * quindi MOCK basta, a differenza di {@code PaForNodeEndpointTest} (RANDOM_PORT).</p>
 *
 * <p>Nessun {@code Clock} fisso iniettato (a differenza della sua controparte quando
 * viveva in govpay-common-pendenze): {@code ADESSO} e' solo il valore con cui questo test
 * popola le fixture, non un {@code @Primary Clock} da sostituire a quello reale di {@code
 * Application} — nessuna asserzione qui confronta un istante generato internamente dal
 * servizio (sempre scritto, mai letto). Un secondo bean {@code @Primary Clock} qui
 * fallirebbe comunque l'avvio: {@code Application} scansiona {@code it.govpay.pafornode}
 * per intero, che a runtime include anche le classi di test compilate nello stesso
 * albero di package — un {@code @TestConfiguration} verrebbe sommato, non sostituito, a
 * quello di {@code PaForNodeEndpointTest} (stesso package), con due bean {@code @Primary}
 * dello stesso tipo in conflitto.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AcquisizioneRicevutaServiceTest {

    private static final OffsetDateTime ADESSO = OffsetDateTime.now(Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"),
            ZoneId.of("Europe/Rome")));

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AcquisizioneRicevutaService service;

    @Autowired
    private PagamentoRepository pagamentoRepository;

    @Autowired
    private RendicontazioneRepository rendicontazioneRepository;

    @Autowired
    private ConnettoreEntityRepository connettoreEntityRepository;

    @Autowired
    private NotificaRepository notificaRepository;

    @Autowired
    private PromemoriaRepository promemoriaRepository;

    @Autowired
    private NotificaAppIoRepository notificaAppIoRepository;

    @Test
    void esitoOkCreaPagamentoEsegueLaPendenzaEAccodaAvvisatura() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-1");
        Long idDominio = dominioPersistito("12345678901");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, true, true, true);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio, "100000000000000001");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-1", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        Rpt aggiornata = service.acquisisci(pendenza, rpt, dati);

        assertThat(aggiornata.getStato()).isEqualTo("RT_ACCETTATA_PA");
        assertThat(aggiornata.getIur()).isEqualTo("receipt-1");
        assertThat(aggiornata.getCodEsitoPagamento()).isEqualTo(0);

        assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
        assertThat(pendenza.getStatoPagamento()).isEqualTo(StatoPagamento.PAGATO);
        assertThat(pendenza.isAnomalo()).isFalse();
        assertThat(pendenza.getVoci().get(0).getStato()).isEqualTo(StatoVocePendenza.ESEGUITO);

        List<Pagamento> pagamenti = pagamentoRepository.findByCodDominioAndIuv(rpt.getCodDominio(), rpt.getIuv());
        assertThat(pagamenti).hasSize(1);
        assertThat(pagamenti.get(0).getImportoPagato()).isEqualTo(10.00);
        assertThat(pagamenti.get(0).getIur()).isEqualTo("receipt-1");

        List<Promemoria> promemoria = promemoriaRepository.findAll();
        assertThat(promemoria).hasSize(1);
        assertThat(promemoria.get(0).getDestinatarioTo()).isEqualTo("debitore@test.it");

        List<NotificaAppIo> notifiche = notificaAppIoRepository.findAll();
        assertThat(notifiche).hasSize(1);
        assertThat(notifiche.get(0).getCodApplicazione()).isEqualTo("APP-1");
        // Il CF reale del debitore principale, non la sentinella
        // Pendenza.debitoreIdentificativo ("VEDERE_SOGGETTI_DEBITORI") delle pendenze v3.
        assertThat(notifiche.get(0).getDebitoreIdentificativo()).isEqualTo("RSSMRA80A01H501U");

        assertThat(pendenza.getOpzionePagamento().getStato()).isEqualTo(StatoOpzionePagamento.ATTIVATA);
    }

    @Test
    void esitoOkAttivaOpzioneEAnnullaLeAlternativeDisponibili() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-5");
        Long idDominio = dominioPersistito("33333333333");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);

        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(applicazione.getId());
        posizione.setCodDocumento("pos-alternative");
        posizione.setIdDominio(idDominio);
        posizione.setDescrizione("test");
        posizione.setNotificaSend(false);
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);

        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setOrdine(0);
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        soggetto.setEmail("debitore@test.it");
        posizione.addSoggettoDebitore(soggetto);

        OpzionePagamento opzionePagata = nuovaOpzioneDisponibile();
        posizione.addOpzionePagamento(opzionePagata);
        Pendenza pendenza = nuovaPendenza(applicazione.getId(), idDominio, idTipoVersamentoDominio, "100000000000000005");
        opzionePagata.addPendenza(pendenza);
        pendenza.addVocePendenza(nuovaVoce("100000000000000005"));

        OpzionePagamento alternativa = nuovaOpzioneDisponibile();
        posizione.addOpzionePagamento(alternativa);
        Pendenza pendenzaAlternativa = nuovaPendenza(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000006");
        alternativa.addPendenza(pendenzaAlternativa);
        pendenzaAlternativa.addVocePendenza(nuovaVoce("100000000000000006"));

        persistAndFlush(posizione);
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-5", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        service.acquisisci(pendenza, rpt, dati);

        assertThat(opzionePagata.getStato()).isEqualTo(StatoOpzionePagamento.ATTIVATA);
        assertThat(alternativa.getStato()).isEqualTo(StatoOpzionePagamento.ANNULLATA);
        assertThat(pendenzaAlternativa.getStato()).isEqualTo(StatoPendenza.ANNULLATO);
    }

    @Test
    void esitoKoNonTocaLaPendenzaNeCreaPagamentiOAvvisatura() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-2");
        Long idDominio = dominioPersistito("10987654321");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, true, true, true);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio, "100000000000000002");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-2", false, ADESSO, 0d, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8), List.of());

        Rpt aggiornata = service.acquisisci(pendenza, rpt, dati);

        assertThat(aggiornata.getStato()).isEqualTo("RT_ACCETTATA_PA");
        assertThat(aggiornata.getCodEsitoPagamento()).isEqualTo(1);
        assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.NON_ESEGUITO);
        assertThat(pendenza.getStatoPagamento()).isEqualTo(StatoPagamento.NON_PAGATO);
        assertThat(pagamentoRepository.findByCodDominioAndIuv(rpt.getCodDominio(), rpt.getIuv())).isEmpty();
        assertThat(promemoriaRepository.findAll()).isEmpty();
        assertThat(notificaAppIoRepository.findAll()).isEmpty();
    }

    @Test
    void avvisaturaDisabilitataNonAccodaNulla() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-3");
        Long idDominio = dominioPersistito("11111111111");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio, "100000000000000003");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-3", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        service.acquisisci(pendenza, rpt, dati);

        assertThat(promemoriaRepository.findAll()).isEmpty();
        assertThat(notificaAppIoRepository.findAll()).isEmpty();
    }

    @Test
    void voceGiaEseguitaVieneMarcataAnomala() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-4");
        Long idDominio = dominioPersistito("22222222222");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio, "100000000000000004");
        pendenza.getVoci().get(0).setStato(StatoVocePendenza.ESEGUITO);
        persistAndFlush(pendenza);
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-4", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        service.acquisisci(pendenza, rpt, dati);

        assertThat(pendenza.isAnomalo()).isTrue();
        assertThat(pendenza.getDescrizioneStato()).isEqualTo("Acquisito pagamento duplicato");
    }

    /**
     * Confronto con la pendenza richiesto dalla Fase 4 dell'issue #6 (bug di revisione
     * corretto, 2026-11-04): senza {@code xmlRpt} (percorso standin) non c'e' nessuna
     * validazione semantica a monte che confronti l'importo della ricevuta con quello
     * atteso dalla pendenza ({@code importo=10.00} di fixture) — una ricevuta da 5 euro
     * viene comunque acquisita (il pagamento e' realmente avvenuto secondo pagoPA), ma la
     * pendenza va marcata anomala, non portata a {@code ESEGUITO} silenziosamente.
     */
    @Test
    void importoDiversoDalPrevistoVieneAcquisitoEMarcatoAnomalo() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-IMPORTO");
        Long idDominio = dominioPersistito("55555555555");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000099");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-importo", true, ADESSO, 5.00, "PSP1",
                "Psp Uno", "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 5.00)));

        service.acquisisci(pendenza, rpt, dati);

        assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
        assertThat(pendenza.isAnomalo()).isTrue();
        // Qui l'unico trasferimento (5.00) e' anche diverso dalla sua voce (10.00): il
        // controllo sul dettaglio (nuovo, bug di revisione corretto, 2026-11-04) lo rileva
        // in aggiunta a quello sul totale — entrambe le note compaiono.
        assertThat(pendenza.getDescrizioneStato()).contains("Importo pagato diverso da quello previsto",
                "Dettaglio dei trasferimenti diverso da quello previsto dalle voci della pendenza");
        assertThat(pagamentoRepository.findByCodDominioAndIuv(rpt.getCodDominio(), rpt.getIuv())).hasSize(1);
    }

    /**
     * Bug di revisione corretto, 2026-11-04 (issue #6): il confronto sul totale deve
     * includere la quota SEND, non solo {@code pendenza.getImporto()} —
     * {@code CtPaymentPaBuilder}/{@code V2Builder} la aggiungono all'importo esposto al
     * Nodo. Una pendenza da 10€ con 2€ di SEND, pagata correttamente per 12€, NON deve
     * risultare anomala.
     */
    @Test
    void importoConSendCorrettoNonVieneMarcatoAnomalo() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-SEND");
        Long idDominio = dominioPersistito("77777777777");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000097");
        pendenza.setSendImportoTotale(2.00);
        persistAndFlush(pendenza);
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        // pendenza.getImporto()=10.00 (fixture) + sendImportoTotale=2.00 = 12.00, tutto
        // sull'unica voce (idDominio nullo, "di competenza dell'ente creditore").
        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-send", true, ADESSO, 12.00, "PSP1",
                "Psp Uno", "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 12.00)));

        service.acquisisci(pendenza, rpt, dati);

        assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
        assertThat(pendenza.isAnomalo()).isFalse();
        assertThat(pendenza.getDescrizioneStato()).isNull();
    }

    /**
     * Bug di revisione corretto, 2026-11-04 (issue #6): il solo confronto sul totale non
     * rileva uno scambio fra voci di importo diverso che si compensa nel totale — voci
     * attese da 6€ e 4€, ricevuta 5€+5€ (stesso totale, 10€) deve comunque risultare
     * anomala sul dettaglio.
     */
    @Test
    void dettaglioTrasferimentiDiversoDalPrevistoVieneMarcatoAnomaloAncheConTotaleCorretto() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-DETTAGLIO");
        Long idDominio = dominioPersistito("88888888888");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        String iuv = "100000000000000096";

        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(applicazione.getId());
        posizione.setCodDocumento("pos-" + iuv);
        posizione.setIdDominio(idDominio);
        posizione.setDescrizione("test");
        posizione.setNotificaSend(false);
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setOrdine(0);
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        soggetto.setEmail("debitore@test.it");
        posizione.addSoggettoDebitore(soggetto);
        OpzionePagamento opzione = nuovaOpzioneDisponibile();
        posizione.addOpzionePagamento(opzione);
        Pendenza pendenza = nuovaPendenza(applicazione.getId(), idDominio, idTipoVersamentoDominio, iuv);
        pendenza.setImporto(10.00);
        opzione.addPendenza(pendenza);
        VocePendenza voce1 = new VocePendenza();
        voce1.setIdVocePendenza("voce-" + iuv + "-1");
        voce1.setImporto(6.00);
        voce1.setDescrizione("voce 1");
        voce1.setIndice(1);
        voce1.setStato(StatoVocePendenza.NON_ESEGUITO);
        pendenza.addVocePendenza(voce1);
        VocePendenza voce2 = new VocePendenza();
        voce2.setIdVocePendenza("voce-" + iuv + "-2");
        voce2.setImporto(4.00);
        voce2.setDescrizione("voce 2");
        voce2.setIndice(2);
        voce2.setStato(StatoVocePendenza.NON_ESEGUITO);
        pendenza.addVocePendenza(voce2);
        persistAndFlush(posizione);
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-dettaglio", true, ADESSO, 10.00, "PSP1",
                "Psp Uno", "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 5.00),
                        new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(2, 5.00)));

        service.acquisisci(pendenza, rpt, dati);

        assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
        assertThat(pendenza.isAnomalo()).isTrue();
        assertThat(pendenza.getDescrizioneStato())
                .contains("Dettaglio dei trasferimenti diverso da quello previsto dalle voci della pendenza")
                .doesNotContain("Importo pagato diverso da quello previsto");
    }

    /**
     * Bug di revisione corretto, 2026-11-04 (issue #6): un trasferimento a importo zero
     * saltava del tutto il confronto con la voce attesa (il {@code continue} precedeva il
     * controllo). Pendenza da 10€, unico voce da 10€, ricevuta con totale dichiarato 10€ ma
     * l'unico trasferimento a 0€: cardinalita' e totale risultavano corretti e la pendenza
     * passava a ESEGUITO senza anomalia. Ora il confronto avviene comunque, prima del
     * {@code continue} — resta invariata solo l'esclusione del trasferimento a zero dalla
     * creazione del Pagamento.
     */
    @Test
    void trasferimentoAImportoZeroVieneComunqueConfrontatoConLaVoceAttesa() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-ZERO");
        Long idDominio = dominioPersistito("99999999999");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000095");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-zero", true, ADESSO, 10.00, "PSP1",
                "Psp Uno", "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 0.00)));

        service.acquisisci(pendenza, rpt, dati);

        assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
        assertThat(pendenza.isAnomalo()).isTrue();
        assertThat(pendenza.getDescrizioneStato())
                .contains("Dettaglio dei trasferimenti diverso da quello previsto dalle voci della pendenza")
                .doesNotContain("Importo pagato diverso da quello previsto");
        assertThat(pagamentoRepository.findByCodDominioAndIuv(rpt.getCodDominio(), rpt.getIuv())).isEmpty();
    }

    /**
     * Opzione ANNULLATA (bug di revisione corretto, 2026-11-04, issue #6): capita quando
     * un'altra opzione della stessa posizione e' stata attivata nel frattempo da un canale
     * di pagamento concorrente. {@code registraEsitoPendenza} rifiuterebbe con {@code
     * TransizioneStatoNonAmmessaException} (ANNULLATA e' definitivo per l'aggregato,
     * {@code ValidatorePosizioneDebitoria#validaStati}) — qui invece si evita del tutto
     * quella chiamata: il pagamento resta registrato (Pagamento scritto, {@code
     * dataPagamento}/{@code importoPagato} impostati), {@code pendenza.stato} resta
     * invariato (coerente con l'opzione ANNULLATA), solo {@code anomalo} si marca.
     */
    @Test
    void ricevutaSuOpzioneAnnullataVieneAcquisitaEMarcataAnomalaSenzaRiattivareLopzione() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-ANNULLATA");
        Long idDominio = dominioPersistito("66666666666");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000098");
        pendenza.getOpzionePagamento().setStato(StatoOpzionePagamento.ANNULLATA);
        pendenza.setStato(StatoPendenza.ANNULLATO);
        persistAndFlush(pendenza);
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-annullata", true, ADESSO, 10.00, "PSP1",
                "Psp Uno", "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        service.acquisisci(pendenza, rpt, dati);

        // Deciso con il committente, 2026-11-04: la pendenza diventa ESEGUITO (il pagamento
        // e' realmente avvenuto), ma l'opzione resta ANNULLATA (stato definitivo per
        // l'aggregato) — incoerenza localizzata e accettata, vedi
        // PosizioneDebitoriaService#registraEsitoPendenzaSuOpzioneAnnullataDaCanaleConcorrente
        // in govpay-common-pendenze e il punto aperto dedicato nel design doc.
        assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
        assertThat(pendenza.isAnomalo()).isTrue();
        assertThat(pendenza.getDescrizioneStato()).isEqualTo("Ricevuta acquisita su un'opzione di pagamento gia' annullata");
        assertThat(pendenza.getOpzionePagamento().getStato()).isEqualTo(StatoOpzionePagamento.ANNULLATA);
        assertThat(pagamentoRepository.findByCodDominioAndIuv(rpt.getCodDominio(), rpt.getIuv())).hasSize(1);
    }

    /**
     * Un pagamento rendicontato PRIMA dell'arrivo della ricevuta ({@code stato =
     * PAGATO_SENZA_RPT}, nessuna RPT collegata) viene aggiornato e collegato qui, non
     * duplicato — stesso comportamento del legacy ({@code CtReceiptUtils.acquisisciRT}:
     * {@code pagamento.getIdRpt() == null} aggiorna, non inserisce).
     */
    @Test
    void pagamentoRendicontatoPrimaDellaRicevutaVieneCollegatoEAggiornato() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-6");
        Long idDominio = dominioPersistito("44444444444");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000007");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        Pagamento rendicontato = new Pagamento();
        rendicontato.setCodDominio(rpt.getCodDominio());
        rendicontato.setIuv(rpt.getIuv());
        rendicontato.setIur("receipt-6");
        rendicontato.setIndiceDati(1);
        rendicontato.setImportoPagato(10.00);
        rendicontato.setDataAcquisizione(ADESSO);
        rendicontato.setDataPagamento(ADESSO);
        rendicontato.setTipo(it.govpay.pendenze.model.TipoPagamento.ENTRATA);
        rendicontato.setStato(StatoPagamentoRicevuta.PAGATO_SENZA_RPT);
        persistAndFlush(rendicontato);
        Long idPagamentoEsistente = rendicontato.getId();

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-6", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        Rpt aggiornata = service.acquisisci(pendenza, rpt, dati);

        List<Pagamento> pagamenti = pagamentoRepository.findByCodDominioAndIuv(rpt.getCodDominio(), rpt.getIuv());
        assertThat(pagamenti).hasSize(1);
        assertThat(pagamenti.get(0).getId()).isEqualTo(idPagamentoEsistente);
        assertThat(pagamenti.get(0).getRpt().getId()).isEqualTo(aggiornata.getId());
        assertThat(pagamenti.get(0).getStato()).isEqualTo(StatoPagamentoRicevuta.PAGATO);
    }

    /** Un pagamento gia' {@code INCASSATO} non viene toccato (stesso {@code continue} del legacy). */
    @Test
    void pagamentoGiaIncassatoNonVieneModificato() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-7");
        Long idDominio = dominioPersistito("55555555555");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000008");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        Pagamento incassato = new Pagamento();
        incassato.setCodDominio(rpt.getCodDominio());
        incassato.setIuv(rpt.getIuv());
        incassato.setIur("receipt-7");
        incassato.setIndiceDati(1);
        incassato.setImportoPagato(10.00);
        incassato.setDataAcquisizione(ADESSO);
        incassato.setDataPagamento(ADESSO);
        incassato.setTipo(it.govpay.pendenze.model.TipoPagamento.ENTRATA);
        incassato.setStato(StatoPagamentoRicevuta.INCASSATO);
        persistAndFlush(incassato);
        Long idPagamentoEsistente = incassato.getId();

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-7", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        service.acquisisci(pendenza, rpt, dati);

        List<Pagamento> pagamenti = pagamentoRepository.findByCodDominioAndIuv(rpt.getCodDominio(), rpt.getIuv());
        assertThat(pagamenti).hasSize(1);
        assertThat(pagamenti.get(0).getId()).isEqualTo(idPagamentoEsistente);
        assertThat(pagamenti.get(0).getStato()).isEqualTo(StatoPagamentoRicevuta.INCASSATO);
        assertThat(pagamenti.get(0).getRpt()).isNull();
    }

    /**
     * Pendenza gia' {@code ESEGUITO} con pagamento gia' {@code INCASSATO}: una ricevuta
     * duplicata/di retry non crea alcun nuovo {@link Pagamento} (nessuna voce da
     * marcare, {@code irregolare} resta {@code false}), ma va comunque segnalata come
     * anomala — la pendenza era gia' in uno stato diverso da
     * {@code NON_ESEGUITO}/{@code ANNULLATO} prima di questa acquisizione.
     */
    @Test
    void ricevutaDuplicataSuPendenzaGiaEseguitaVieneSegnalataAnomala() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-9");
        Long idDominio = dominioPersistito("77777777777");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000010");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        pendenza.setStato(StatoPendenza.ESEGUITO);
        persistAndFlush(pendenza);

        Pagamento incassato = new Pagamento();
        incassato.setCodDominio(rpt.getCodDominio());
        incassato.setIuv(rpt.getIuv());
        incassato.setIur("receipt-9");
        incassato.setIndiceDati(1);
        incassato.setImportoPagato(10.00);
        incassato.setDataAcquisizione(ADESSO);
        incassato.setDataPagamento(ADESSO);
        incassato.setTipo(it.govpay.pendenze.model.TipoPagamento.ENTRATA);
        incassato.setStato(StatoPagamentoRicevuta.INCASSATO);
        persistAndFlush(incassato);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-9", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        service.acquisisci(pendenza, rpt, dati);

        assertThat(pendenza.isAnomalo()).isTrue();
        assertThat(pendenza.getStatoPagamento()).isEqualTo(StatoPagamento.PAGATO);
    }

    /**
     * Un pagamento con la stessa chiave (dominio/iuv/iur/indice) gia' collegato a
     * un'ALTRA RPT fa fallire l'acquisizione con un errore esplicito, non lo sovrascrive
     * silenziosamente — stesso comportamento del legacy ({@code ServiceException} su
     * {@code pagamento.getIdRpt() != null}).
     */
    @Test
    void pagamentoGiaCollegatoAUnaltraRptFallisceConErroreEsplicito() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-8");
        Long idDominio = dominioPersistito("66666666666");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000009");
        Rpt rptOriginale = rptAccettataNodoPersistita(pendenza);

        Rpt rptNuova = new Rpt();
        rptNuova.setIdVersamento(pendenza.getId());
        rptNuova.setIuv(pendenza.getIuv());
        rptNuova.setIur("ccp-nuova-" + pendenza.getId());
        rptNuova.setCodDominio(rptOriginale.getCodDominio());
        rptNuova.setCodStazione("STAZIONE-1");
        rptNuova.setCodMsgRichiesta("msg-nuova-" + pendenza.getId());
        rptNuova.setStato("RPT_ACCETTATA_NODO");
        rptNuova.setDataMsgRichiesta(ADESSO);
        rptNuova.setDataAggiornamentoStato(ADESSO);
        rptNuova.setVersione("SANP_240");
        persistAndFlush(rptNuova);

        Pagamento collegato = new Pagamento();
        collegato.setCodDominio(rptOriginale.getCodDominio());
        collegato.setIuv(rptOriginale.getIuv());
        collegato.setIur("receipt-8");
        collegato.setIndiceDati(1);
        collegato.setImportoPagato(10.00);
        collegato.setDataAcquisizione(ADESSO);
        collegato.setDataPagamento(ADESSO);
        collegato.setTipo(it.govpay.pendenze.model.TipoPagamento.ENTRATA);
        collegato.setStato(StatoPagamentoRicevuta.PAGATO);
        collegato.setRpt(rptOriginale);
        persistAndFlush(collegato);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-8", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        assertThatThrownBy(() -> service.acquisisci(pendenza, rptNuova, dati))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Una {@code Rendicontazione} ANOMALA arrivata prima della ricevuta (stessi estremi
     * del pagamento, nessun pagamento collegato) viene richiusa e collegata al pagamento
     * appena acquisito — porting di {@code RtUtils
     * #checkEsistenzaRendicontazioneAnomalaPerIlPagamento}.
     */
    @Test
    void rendicontazioneAnomalaPreesistenteVieneRichiusaECollegata() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-9");
        Long idDominio = dominioPersistito("77777777777");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000010");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        Rendicontazione rendicontazioneAnomala = rendicontazioneAnomalaPersistita(rpt.getCodDominio(), rpt.getIuv(),
                "receipt-9", 1);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-9", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        service.acquisisci(pendenza, rpt, dati);

        Rendicontazione rilettura = rendicontazioneRepository.findById(rendicontazioneAnomala.getId()).orElseThrow();
        assertThat(rilettura.getStato()).isEqualTo(StatoRendicontazione.OK);
        assertThat(rilettura.getAnomalie()).isNull();
        Pagamento pagamento = pagamentoRepository.findByCodDominioAndIuv(rpt.getCodDominio(), rpt.getIuv()).get(0);
        assertThat(rilettura.getIdPagamento()).isEqualTo(pagamento.getId());
        assertThat(rilettura.getIdSingoloVersamento()).isEqualTo(pendenza.getVoci().get(0).getId());
    }

    /**
     * Una rendicontazione con {@code indiceDati} assente ({@code null}) corrisponde a
     * QUALUNQUE indice richiesto — stesso comportamento del legacy
     * ({@code RendicontazioniBD#getRendicontazione}: {@code indiceDati = ? OR indiceDati
     * IS NULL}), non un mancato confronto.
     */
    @Test
    void rendicontazioneAnomalaConIndiceDatiAssenteCorrispondeComunque() {
        ApplicazioneEntity applicazione = applicazionePersistita("APP-10");
        Long idDominio = dominioPersistito("88888888888");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000011");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        Rendicontazione rendicontazioneAnomala = rendicontazioneAnomalaPersistita(rpt.getCodDominio(), rpt.getIuv(),
                "receipt-10", null);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-10", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        service.acquisisci(pendenza, rpt, dati);

        Rendicontazione rilettura = rendicontazioneRepository.findById(rendicontazioneAnomala.getId()).orElseThrow();
        assertThat(rilettura.getStato()).isEqualTo(StatoRendicontazione.OK);
    }

    /**
     * La notifica RICEVUTA viene accodata sull'esito OK, anche su un connettore v2
     * (nessun filtro di versione, a differenza di ATTIVAZIONE — vedi Javadoc di
     * {@code NotificaAttivazioneService}).
     */
    @Test
    void accodaNotificaRicevutaSuEsitoOk() {
        ApplicazioneEntity applicazione = applicazioneConConnettorePersistita("APP-11", "CONN-RIC-11", "REST_2");
        Long idDominio = dominioPersistito("99999999991");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000012");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-11", true, ADESSO, 10.00, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8),
                List.of(new DatiRicevutaPagamento.DatiTrasferimentoRicevuto(1, 10.00)));

        service.acquisisci(pendenza, rpt, dati);

        List<Notifica> notifiche = notificaRepository.findAll();
        assertThat(notifiche).hasSize(1);
        assertThat(notifiche.get(0).getTipo()).isEqualTo(TipoNotifica.RICEVUTA);
        assertThat(notifiche.get(0).getIdRpt()).isEqualTo(rpt.getId());
    }

    /**
     * A differenza di pagamenti/avvisatura (nessun side-effect su KO, vedi Javadoc di
     * classe del servizio), la notifica RICEVUTA viene accodata ANCHE su esito KO — stesso
     * comportamento del legacy, l'EC va informato anche dell'esito negativo.
     */
    @Test
    void accodaNotificaRicevutaAncheSuEsitoKo() {
        ApplicazioneEntity applicazione = applicazioneConConnettorePersistita("APP-12", "CONN-RIC-12", "REST_2");
        Long idDominio = dominioPersistito("99999999992");
        Long idTipoVersamentoDominio = tipoVersamentoDominioPersistito(idDominio, false, false, false);
        Pendenza pendenza = pendenzaPersistita(applicazione.getId(), idDominio, idTipoVersamentoDominio,
                "100000000000000013");
        Rpt rpt = rptAccettataNodoPersistita(pendenza);

        DatiRicevutaPagamento dati = new DatiRicevutaPagamento("receipt-12", false, ADESSO, 0d, "PSP1", "Psp Uno",
                "ID-PSP-1", "CH-1", "BBT", "<Receipt/>".getBytes(StandardCharsets.UTF_8), List.of());

        service.acquisisci(pendenza, rpt, dati);

        List<Notifica> notifiche = notificaRepository.findAll();
        assertThat(notifiche).hasSize(1);
        assertThat(notifiche.get(0).getTipo()).isEqualTo(TipoNotifica.RICEVUTA);
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private <T> T persistAndFlush(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }

    private ApplicazioneEntity applicazionePersistita(String codApplicazione) {
        ApplicazioneEntity applicazione = ApplicazioneEntity.builder().codApplicazione(codApplicazione)
                .autoIuv(true).firmaRicevuta("N").trusted(true)
                .idUtenza((long) Math.abs(codApplicazione.hashCode())).build();
        return persistAndFlush(applicazione);
    }

    private ApplicazioneEntity applicazioneConConnettorePersistita(String codApplicazione, String codConnettore,
            String versione) {
        ApplicazioneEntity applicazione = ApplicazioneEntity.builder().codApplicazione(codApplicazione)
                .autoIuv(true).firmaRicevuta("N").trusted(true)
                .idUtenza((long) Math.abs(codApplicazione.hashCode())).codConnettoreIntegrazione(codConnettore)
                .build();
        persistAndFlush(applicazione);
        connettoreEntityRepository.save(ConnettoreEntity.builder().codConnettore(codConnettore)
                .codProprieta("VERSIONE").valore(versione).build());
        return applicazione;
    }

    private Long dominioPersistito(String codDominio) {
        DominioEntity dominio = DominioEntity.builder().codDominio(codDominio).abilitato(true)
                .ragioneSociale("test").auxDigit(0).intermediato(false).scaricaFr(false).build();
        persistAndFlush(dominio);
        return dominio.getId();
    }

    private Long tipoVersamentoDominioPersistito(Long idDominio, boolean mailAbilitato, boolean mailPdf,
            boolean appIoAbilitato) {
        TipoVersamentoEntity tipoVersamento = new TipoVersamentoEntity();
        tipoVersamento.setCodTipoVersamento("IMU-" + idDominio);
        tipoVersamento.setDescrizione("test");
        tipoVersamento.setAbilitato(true);
        tipoVersamento.setPagaTerzi(false);
        tipoVersamento.setBoAbilitato(false);
        tipoVersamento.setPagAbilitato(false);
        tipoVersamento.setAvvMailPromAvvAbilitato(false);
        tipoVersamento.setAvvMailPromRicAbilitato(false);
        tipoVersamento.setAvvMailPromScadAbilitato(false);
        tipoVersamento.setAvvAppIoPromAvvAbilitato(false);
        tipoVersamento.setAvvAppIoPromRicAbilitato(false);
        tipoVersamento.setAvvAppIoPromScadAbilitato(false);
        persistAndFlush(tipoVersamento);

        TipoVersamentoDominioEntity tipoVersamentoDominio = new TipoVersamentoDominioEntity();
        tipoVersamentoDominio.setDominio(em.find(DominioEntity.class, idDominio));
        tipoVersamentoDominio.setTipoVersamento(tipoVersamento);
        tipoVersamentoDominio.setAvvMailPromRicAbilitato(mailAbilitato);
        tipoVersamentoDominio.setAvvMailPromRicPdf(mailPdf);
        tipoVersamentoDominio.setAvvAppIoPromRicAbilitato(appIoAbilitato);
        persistAndFlush(tipoVersamentoDominio);
        return tipoVersamentoDominio.getId();
    }

    private Pendenza pendenzaPersistita(Long idApplicazione, Long idDominio, Long idTipoVersamentoDominio, String iuv) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazione);
        posizione.setCodDocumento("pos-" + iuv);
        posizione.setIdDominio(idDominio);
        posizione.setDescrizione("test");
        posizione.setNotificaSend(false);
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);

        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setOrdine(0);
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        soggetto.setEmail("debitore@test.it");
        posizione.addSoggettoDebitore(soggetto);

        OpzionePagamento opzione = nuovaOpzioneDisponibile();
        posizione.addOpzionePagamento(opzione);

        Pendenza pendenza = nuovaPendenza(idApplicazione, idDominio, idTipoVersamentoDominio, iuv);
        opzione.addPendenza(pendenza);
        pendenza.addVocePendenza(nuovaVoce(iuv));

        persistAndFlush(posizione);
        return pendenza;
    }

    private OpzionePagamento nuovaOpzioneDisponibile() {
        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setIdOpzionePagamento(UUID.randomUUID());
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        opzione.setDataCreazione(ADESSO);
        opzione.setDataUltimoAggiornamento(ADESSO);
        return opzione;
    }

    private Pendenza nuovaPendenza(Long idApplicazione, Long idDominio, Long idTipoVersamentoDominio, String iuv) {
        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(idDominio);
        pendenza.setIdApplicazione(idApplicazione);
        pendenza.setIdPendenza("pendenza-" + iuv);
        pendenza.setIdTipoPendenza(idTipoVersamentoDominio);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setNumeroRata(1);
        pendenza.setImporto(10.00);
        pendenza.setNumeroAvviso(iuv);
        pendenza.setIuv(iuv);
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataUltimoAggiornamento(ADESSO);
        return pendenza;
    }

    private VocePendenza nuovaVoce(String iuv) {
        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + iuv);
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        return voce;
    }

    private Rpt rptAccettataNodoPersistita(Pendenza pendenza) {
        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv(pendenza.getIuv());
        rpt.setIur("ccp-provvisorio-" + pendenza.getId());
        rpt.setCodDominio("DOM-" + pendenza.getIdDominio());
        rpt.setCodStazione("STAZIONE-1");
        rpt.setCodMsgRichiesta("msg-" + pendenza.getId());
        rpt.setStato("RPT_ACCETTATA_NODO");
        rpt.setDataMsgRichiesta(ADESSO);
        rpt.setDataAggiornamentoStato(ADESSO);
        rpt.setVersione("SANP_240");
        return persistAndFlush(rpt);
    }

    private Rendicontazione rendicontazioneAnomalaPersistita(String codDominio, String iuv, String iur,
            Integer indiceDati) {
        FlussoRendicontazione flusso = new FlussoRendicontazione();
        flusso.setIdDominio(1L);
        flusso.setCodDominio(codDominio);
        flusso.setCodFlusso("flusso-" + iur);
        flusso.setDataOraFlusso(ADESSO);
        flusso.setIur("iur-flusso-" + iur);
        flusso.setDataAcquisizione(ADESSO);
        flusso.setCodPsp("PSP1");
        flusso.setStato(StatoFlussoRendicontazione.ACCETTATA);
        flusso.setObsoleto(false);
        persistAndFlush(flusso);

        Rendicontazione rendicontazione = new Rendicontazione();
        rendicontazione.setFlusso(flusso);
        rendicontazione.setIuv(iuv);
        rendicontazione.setIur(iur);
        rendicontazione.setIndiceDati(indiceDati);
        rendicontazione.setImportoPagato(10.00);
        rendicontazione.setEsito(0);
        rendicontazione.setData(ADESSO);
        rendicontazione.setStato(StatoRendicontazione.ANOMALA);
        rendicontazione.setAnomalie("Pagamento rendicontato ma non ancora riscontrato");
        return persistAndFlush(rendicontazione);
    }
}
