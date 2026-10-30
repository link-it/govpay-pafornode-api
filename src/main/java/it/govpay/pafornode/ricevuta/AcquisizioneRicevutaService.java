package it.govpay.pafornode.ricevuta;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.pendenze.entity.Pagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoPagamentoRicevuta;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.entity.Rendicontazione;
import it.govpay.pendenze.model.StatoRendicontazione;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoPagamento;
import it.govpay.pendenze.repository.PagamentoRepository;
import it.govpay.pendenze.repository.RendicontazioneRepository;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pafornode.rpt.NotificaAttivazioneService;

/**
 * Acquisizione di una ricevuta di pagamento (RT) pagoPA su una RPT gia' attivata —
 * porting di {@code CtReceiptUtils.acquisisciRT}/{@code RtUtils} del monolite
 * (rispettivamente {@code jars/core/.../utils/CtReceiptUtils.java} e {@code RtUtils.java}),
 * limitato al percorso principale (RPT trovata, ricevuta valida): usato da
 * {@code PaSendRTService} ({@code paSendRT}/{@code paSendRTV2}).
 *
 * <p><b>Perche' qui e non in govpay-common-pendenze</b>: nata in quella libreria con il
 * criterio "utile anche a un futuro recupero RT" (stesso principio di
 * {@code PosizioneDebitoriaService#registraEsitoPendenza}/{@code #registraImportoSend}).
 * Spostata qui perche' pafornode-api e' l'UNICO punto da cui GovPay acquisisce ricevute
 * pagoPA: non esiste (e non e' previsto) un secondo consumer che ne giustifichi la
 * condivisione — tenere logica di scrittura e controlli applicativi della stessa primitiva
 * nello stesso modulo evita un salto tra repository per seguirne il flusso completo.
 * Dipende comunque da {@link PosizioneDebitoriaService} (govpay-common-pendenze, dependency
 * esistente) per la transizione dell'opzione di pagamento — nessuna dipendenza nuova tra i
 * due moduli.</p>
 *
 * <p>Non dipende dai tipi JAXB del contratto paForNode (a differenza di
 * {@link it.govpay.pendenze.ricevuta.pagopa.RicostruttoreRichiestaPagamento} di
 * govpay-common-pendenze, che li usa per un motivo diverso — ricostruire un payload mai
 * salvato, non acquisirne uno appena arrivato): il chiamante ({@code PaSendRTService})
 * estrae gia' i valori grezzi in {@link DatiRicevutaPagamento}, stesso principio
 * generico-con-booleani-gia'-risolti di {@code AutorizzazioneDominio.autorizzato} in
 * govpay-common-auth.</p>
 *
 * <p><b>Il chiamante risolve e blocca {@link Pendenza}/{@link Rpt} prima di invocare
 * questo servizio</b> (idempotenza su {@code receiptId} e ricerca della RPT in attesa di
 * ricevuta incluse, tramite {@code RptRepository#findByCodDominioAndIuvAndIur}/
 * {@code #findByIdVersamentoAndStato}): la validazione semantica RT-contro-RPT richiede
 * il payload della richiesta originale deserializzato
 * ({@code RptAttivazioneService#deserializzaDati}/{@code V2}), fatta da
 * {@code PaSendRTService} prima di chiamare questo servizio, non da questo servizio
 * stesso.</p>
 *
 * <p><b>Transizione dell'opzione di pagamento</b>: l'esito OK non scrive direttamente
 * {@code pendenza.stato} — delega a
 * {@link PosizioneDebitoriaService#registraEsitoPendenza}, che attiva l'opzione e annulla
 * automaticamente le alternative ancora disponibili della stessa posizione, con le stesse
 * garanzie di concorrenza/congruenza di qualunque altro esito di pagamento registrato
 * sull'aggregato.</p>
 *
 * <p><b>Esito binario</b>: a differenza del vecchio SANP_230 (dove
 * {@code RtUtils.validaSemanticaCodiceEsitoPagamento} riconosce anche esiti parziali/
 * decorrenza termini), il contratto paForNode (SANP_240/321_V2) riporta solo
 * {@code StOutcome} OK/KO — {@link DatiRicevutaPagamento#eseguito()}. Verificato che
 * {@code RtUtils#impostaNuovoStatoVersamento} per un esito
 * {@code PAGAMENTO_NON_ESEGUITO}/{@code DECORRENZA_TERMINI} non modifica affatto lo stato
 * della pendenza ({@code break} senza side-effect): per KO questo metodo aggiorna comunque
 * la RPT (stato/esito/xmlRt) ma lascia {@link Pendenza#getStato()} invariato, nessun
 * {@link Pagamento} ne' riga di avvisatura viene creata (nessun trasferimento esecuito da
 * registrare).</p>
 *
 * <p><b>Non implementato, documentato come punto aperto</b> (vedi
 * {@code proposta-migrazione-pafornode-v3.md} §3octies): la ricostruzione "standin" di
 * un'RPT mai attivata da GovPay ({@code CtReceiptUtils.ricostruisciRPT} nel monolite) e la
 * validazione semantica completa nel caso ibrido RPTV2_RTV1 — entrambi casi di minoranza,
 * il percorso principale (RPT regolarmente attivata da {@code paGetPayment}/
 * {@code paGetPaymentV2}, ricevuta nello stesso formato) e' quello implementato qui.</p>
 */
@Service
public class AcquisizioneRicevutaService {

    private final PagamentoRepository pagamentoRepository;
    private final PromemoriaRepository promemoriaRepository;
    private final NotificaAppIoRepository notificaAppIoRepository;
    private final TipoVersamentoDominioRepository tipoVersamentoDominioRepository;
    private final ApplicazioneRepository applicazioneRepository;
    private final RendicontazioneRepository rendicontazioneRepository;
    private final PosizioneDebitoriaService posizioneDebitoriaService;
    private final NotificaAttivazioneService notificaAttivazioneService;
    private final Clock clock;

    public AcquisizioneRicevutaService(PagamentoRepository pagamentoRepository,
            PromemoriaRepository promemoriaRepository, NotificaAppIoRepository notificaAppIoRepository,
            TipoVersamentoDominioRepository tipoVersamentoDominioRepository,
            ApplicazioneRepository applicazioneRepository, RendicontazioneRepository rendicontazioneRepository,
            PosizioneDebitoriaService posizioneDebitoriaService, NotificaAttivazioneService notificaAttivazioneService,
            Clock clock) {
        this.pagamentoRepository = pagamentoRepository;
        this.promemoriaRepository = promemoriaRepository;
        this.notificaAppIoRepository = notificaAppIoRepository;
        this.tipoVersamentoDominioRepository = tipoVersamentoDominioRepository;
        this.applicazioneRepository = applicazioneRepository;
        this.rendicontazioneRepository = rendicontazioneRepository;
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.notificaAttivazioneService = notificaAttivazioneService;
        this.clock = clock;
    }

    /**
     * Acquisisce la ricevuta sulla RPT indicata, gia' individuata e bloccata dal
     * chiamante (vedi Javadoc di classe).
     *
     * @param pendenza la pendenza, con {@link Pendenza#getVoci()} nello stesso ordine dei
     *                 trasferimenti della ricevuta (assunzione posizionale, come il legacy)
     * @param rpt      la RPT in attesa di ricevuta, gia' bloccata dal chiamante
     * @param dati     i dati della ricevuta, gia' validata semanticamente dal chiamante
     * @return la RPT aggiornata
     * @throws IllegalStateException se un {@link Pagamento} con la stessa chiave
     *         (dominio/iuv/iur/indice) e' gia' collegato a un'altra RPT
     */
    @Transactional
    public Rpt acquisisci(Pendenza pendenza, Rpt rpt, DatiRicevutaPagamento dati) {
        OffsetDateTime adesso = OffsetDateTime.now(clock);

        rpt.setIur(dati.receiptId());
        rpt.setDataMsgRicevuta(adesso);
        rpt.setDataAggiornamentoStato(adesso);
        rpt.setCodEsitoPagamento(dati.eseguito() ? 0 : 1);
        rpt.setXmlRt(dati.xmlRt());
        rpt.setTipoVersamento(dati.tipoVersamento());
        rpt.setStato("RT_ACCETTATA_PA");
        rpt.setDescrizioneStato(null);

        if (!dati.eseguito()) {
            // Esito KO: la RPT resta aggiornata, ma nessun pagamento e' stato eseguito —
            // nessun side-effect sulla pendenza (vedi Javadoc di classe). La notifica
            // RICEVUTA viene accodata comunque, anche per KO (stesso comportamento del
            // legacy — l'EC va informato dell'esito negativo).
            notificaAttivazioneService.accodaRicevuta(pendenza, rpt);
            return rpt;
        }

        StatoPendenza statoPrecedente = pendenza.getStato();
        List<VocePendenza> voci = pendenza.getVoci();
        List<DatiRicevutaPagamento.DatiTrasferimentoRicevuto> trasferimenti = dati.trasferimenti();
        boolean irregolare = false;
        for (int indice = 0; indice < trasferimenti.size(); indice++) {
            DatiRicevutaPagamento.DatiTrasferimentoRicevuto trasferimento = trasferimenti.get(indice);
            if (trasferimento.transferAmount() == 0d) {
                continue;
            }
            VocePendenza voce = indice < voci.size() ? voci.get(indice) : null;

            Optional<Pagamento> esistente = pagamentoRepository
                    .findByCodDominioAndIuv(rpt.getCodDominio(), rpt.getIuv()).stream()
                    .filter(p -> p.getIur().equals(dati.receiptId()) && p.getIndiceDati() == trasferimento.idTransfer())
                    .findFirst();
            if (esistente.isPresent() && esistente.get().getStato() == StatoPagamentoRicevuta.INCASSATO) {
                // Gia' incassato: nessuna modifica.
                continue;
            }
            if (esistente.isPresent() && esistente.get().getRpt() != null) {
                throw new IllegalStateException("Pagamento [dominio:" + rpt.getCodDominio() + ", iuv:" + rpt.getIuv()
                        + ", iur:" + dati.receiptId() + ", indice:" + trasferimento.idTransfer()
                        + "] e' gia' collegato alla RPT [id:" + esistente.get().getRpt().getId() + "].");
            }

            // Se esiste gia' (rendicontato prima della ricevuta, stato PAGATO_SENZA_RPT,
            // nessuna RPT collegata) viene aggiornato e collegato qui, non ricreato.
            Pagamento pagamento = esistente.orElseGet(Pagamento::new);
            boolean nuovo = pagamento.getId() == null;
            pagamento.setCodDominio(rpt.getCodDominio());
            pagamento.setIuv(rpt.getIuv());
            pagamento.setIur(dati.receiptId());
            pagamento.setIndiceDati(trasferimento.idTransfer());
            pagamento.setImportoPagato(trasferimento.transferAmount());
            pagamento.setDataAcquisizione(adesso);
            pagamento.setDataPagamento(dati.dataPagamento() != null ? dati.dataPagamento() : adesso);
            pagamento.setTipo(determinaTipoPagamento(voce));
            pagamento.setRpt(rpt);
            if (pagamento.getStato() == null || pagamento.getStato() == StatoPagamentoRicevuta.PAGATO_SENZA_RPT) {
                pagamento.setStato(StatoPagamentoRicevuta.PAGATO);
            }
            pagamentoRepository.save(pagamento);

            if (nuovo && voce != null) {
                if (voce.getStato() == StatoVocePendenza.ESEGUITO) {
                    irregolare = true;
                } else {
                    voce.setStato(StatoVocePendenza.ESEGUITO);
                }
            }
            if (nuovo && !irregolare) {
                riconciliaRendicontazioneAnomala(rpt.getCodDominio(), rpt.getIuv(), dati.receiptId(),
                        trasferimento.idTransfer(), pagamento, voce);
            }
        }

        pendenza.setDataPagamento(dati.dataPagamento() != null ? dati.dataPagamento() : adesso);
        pendenza.setImportoPagato(dati.importoTotalePagato());
        pendenza.setStatoPagamento(StatoPagamento.PAGATO);
        if (irregolare) {
            pendenza.setDescrizioneStato("Acquisito pagamento duplicato");
        }
        // Anomalo anche quando la pendenza era gia' in uno stato diverso da
        // NON_ESEGUITO/ANNULLATO prima di questa acquisizione, a prescindere da
        // 'irregolare' (es. tutti i trasferimenti gia' INCASSATI: nessun Pagamento
        // nuovo, ma la ricevuta e' comunque un duplicato/retry su una pendenza gia'
        // conclusa).
        boolean giaEseguita = statoPrecedente != StatoPendenza.NON_ESEGUITO
                && statoPrecedente != StatoPendenza.ANNULLATO;
        if (irregolare || giaEseguita) {
            pendenza.setAnomalo(true);
        }
        posizioneDebitoriaService.registraEsitoPendenza(risolviCodApplicazione(pendenza.getIdApplicazione()),
                pendenza.getIdPendenza(), StatoPendenza.ESEGUITO);

        accodaAvvisatura(pendenza, rpt, adesso);
        notificaAttivazioneService.accodaRicevuta(pendenza, rpt);

        return rpt;
    }

    /**
     * {@code pagamento.tipo}: {@code MBT} se la voce ha un tributo bollo (qui: nessun
     * tributo risolvibile da questa libreria, M4 — una voce di marca da bollo non ha mai
     * un {@code idTributo}, vedi {@code VocePendenza}/{@code CtPaymentPaV2Builder} — si
     * riconosce dalla presenza di {@code tipoBollo}), altrimenti {@code ENTRATA}. Il ramo
     * legacy {@code ENTRATA_PA_NON_INTERMEDIATA} (dominio non intermediato) e'
     * un'informazione di {@code DominioEntity} (governpay-common), non disponibile qui:
     * lasciato a {@code ENTRATA}, coerente con il fatto che questa libreria non legge
     * l'anagrafica dominio per un semplice pagamento (M4).
     */
    /**
     * Richiude una {@link Rendicontazione} ANOMALA arrivata PRIMA della ricevuta
     * corrispondente (stessi estremi del pagamento appena acquisito, non ancora collegata
     * a nessun pagamento) — porting di {@code RtUtils
     * #checkEsistenzaRendicontazioneAnomalaPerIlPagamento}. Nessuna azione se non ne
     * esiste una: e' il caso normale, non un errore.
     */
    private void riconciliaRendicontazioneAnomala(String codDominio, String iuv, String iur, int indiceDati,
            Pagamento pagamento, VocePendenza voce) {
        rendicontazioneRepository.findAnomalaNonCollegata(codDominio, iuv, iur, indiceDati, StatoRendicontazione.ANOMALA)
                .stream().findFirst().ifPresent(rendicontazione -> {
                    rendicontazione.setStato(StatoRendicontazione.OK);
                    rendicontazione.setAnomalie(null);
                    rendicontazione.setIdPagamento(pagamento.getId());
                    if (rendicontazione.getIdSingoloVersamento() == null && voce != null) {
                        rendicontazione.setIdSingoloVersamento(voce.getId());
                    }
                    rendicontazioneRepository.save(rendicontazione);
                });
    }

    private TipoPagamento determinaTipoPagamento(VocePendenza voce) {
        if (voce != null && voce.getTipoBollo() != null) {
            return TipoPagamento.MBT;
        }
        return TipoPagamento.ENTRATA;
    }

    /**
     * Accoda {@link Promemoria}/{@link NotificaAppIo} di tipo RICEVUTA, se abilitati per
     * questo tipo di pendenza (override per dominio, altrimenti default globale — fallback
     * gia' presente su {@code TipoVersamentoDominioEntity}/{@code TipoVersamentoEntity},
     * issue govpay-common#9). Solo l'accodamento: la spedizione reale (SMTP/AppIO) resta
     * un batch separato non ancora portato (vedi Javadoc di {@link Promemoria}).
     */
    private void accodaAvvisatura(Pendenza pendenza, Rpt rpt, OffsetDateTime adesso) {
        TipoVersamentoDominioEntity tipoVersamentoDominio = tipoVersamentoDominioRepository
                .findByIdFetchTipoVersamento(pendenza.getIdTipoPendenza()).orElse(null);
        if (tipoVersamentoDominio == null) {
            return;
        }
        TipoVersamentoEntity tipoVersamento = tipoVersamentoDominio.getTipoVersamento();

        if (risolviFlag(tipoVersamentoDominio.getAvvMailPromRicAbilitato(), tipoVersamento.getAvvMailPromRicAbilitato())) {
            SoggettoDebitore debitore = debitorePrincipale(pendenza);
            if (debitore != null && debitore.getEmail() != null) {
                Promemoria promemoria = new Promemoria();
                promemoria.setIdVersamento(pendenza.getId());
                promemoria.setIdRpt(rpt.getId());
                promemoria.setTipo(TipoPromemoria.RICEVUTA);
                promemoria.setDataCreazione(adesso);
                promemoria.setDataAggiornamentoStato(adesso);
                promemoria.setDataProssimaSpedizione(adesso);
                promemoria.setTentativiSpedizione(0L);
                promemoria.setStato(StatoSpedizionePromemoria.DA_SPEDIRE);
                promemoria.setDestinatarioTo(debitore.getEmail());
                promemoria.setAllegaPdf(risolviFlag(tipoVersamentoDominio.getAvvMailPromRicPdf(),
                        tipoVersamento.getAvvMailPromRicPdf()));
                promemoriaRepository.save(promemoria);
            }
        }

        if (risolviFlag(tipoVersamentoDominio.getAvvAppIoPromRicAbilitato(),
                tipoVersamento.getAvvAppIoPromRicAbilitato())) {
            SoggettoDebitore debitore = debitorePrincipale(pendenza);
            if (debitore != null && debitore.getIdentificativo() != null) {
                NotificaAppIo notificaAppIo = new NotificaAppIo();
                notificaAppIo.setIdVersamento(pendenza.getId());
                notificaAppIo.setIdTipoVersamentoDominio(tipoVersamentoDominio.getId());
                notificaAppIo.setIdRpt(rpt.getId());
                notificaAppIo.setDebitoreIdentificativo(debitore.getIdentificativo());
                notificaAppIo.setCodVersamentoEnte(pendenza.getIdPendenza());
                notificaAppIo.setCodApplicazione(risolviCodApplicazione(pendenza.getIdApplicazione()));
                notificaAppIo.setCodDominio(rpt.getCodDominio());
                notificaAppIo.setIuv(rpt.getIuv());
                notificaAppIo.setTipo(TipoNotificaAppIo.RICEVUTA);
                notificaAppIo.setDataCreazione(adesso);
                notificaAppIo.setDataAggiornamentoStato(adesso);
                notificaAppIo.setDataProssimaSpedizione(adesso);
                notificaAppIo.setTentativiSpedizione(0L);
                notificaAppIo.setStato(StatoSpedizioneNotificaAppIo.DA_SPEDIRE);
                notificaAppIoRepository.save(notificaAppIo);
            }
        }
    }

    /**
     * {@code pendenza.getDebitoreIdentificativo()} e' una sentinella fissa
     * ({@code VEDERE_SOGGETTI_DEBITORI}) sulle pendenze v3-native, non il codice fiscale
     * reale: la fonte di verita' e' sempre {@link #debitorePrincipale}, mai questo campo
     * denormalizzato (vedi Javadoc di {@code Pendenza#debitoreIdentificativo}).
     */
    private String risolviCodApplicazione(Long idApplicazione) {
        return applicazioneRepository.findById(idApplicazione).map(ApplicazioneEntity::getCodApplicazione)
                .orElse(null);
    }

    /** {@code custom} vince se non {@code null}, altrimenti {@code defaultGlobale} (mai {@code null} in produzione). */
    private boolean risolviFlag(Boolean custom, Boolean defaultGlobale) {
        if (custom != null) {
            return custom;
        }
        return Boolean.TRUE.equals(defaultGlobale);
    }

    /** Il debitore di {@code ordine} piu' basso, stesso principio del "soggetto di ordine 0" legacy. */
    private SoggettoDebitore debitorePrincipale(Pendenza pendenza) {
        return pendenza.getOpzionePagamento().getPosizioneDebitoria().getSoggettiDebitori().stream()
                .min((a, b) -> Integer.compare(a.getOrdine(), b.getOrdine())).orElse(null);
    }
}
