package it.govpay.pafornode.ricevuta;

import static org.assertj.core.api.Assertions.assertThat;

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
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.PagamentoRepository;

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
}
