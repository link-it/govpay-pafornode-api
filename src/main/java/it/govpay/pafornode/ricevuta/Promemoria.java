package it.govpay.pafornode.ricevuta;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import it.govpay.pendenze.entity.Pagamento;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;

/**
 * Promemoria email in coda di spedizione (es. ricevuta di pagamento), mappato sulla
 * tabella legacy {@code promemoria} — riuso diretto.
 *
 * <p><b>Solo i campi scritti all'accodamento</b>: {@code oggetto}/{@code messaggio}/
 * {@code messaggio_content_type} restano {@code NULL} qui — nel legacy sono valorizzati a
 * SPEDIZIONE (non ad accodamento) dal motore di template
 * ({@code it.govpay.core.business.Promemoria#invioPromemoriaRicevuta} del monolite,
 * ~550 righe di rendering/SMTP), un batch separato non ancora portato e fuori dal
 * perimetro di chi accoda (vedi {@code proposta-migrazione-pafornode-v3.md}
 * §3octies — stesso principio di "solo accodamento" gia' usato per
 * {@code NotificaAttivazioneService}). Stesso discorso per {@link #idDocumento}
 * (promemoria di tipo AVVISO/SCADENZA, non RICEVUTA: non applicabile a questo caso
 * d'uso).</p>
 *
 * <p>Fuori dall'aggregato {@link PosizioneDebitoria}, stesso principio di {@link Rpt}/
 * {@link Pagamento}: {@link #idVersamento}/{@link #idRpt} sono FK piatte, non relazioni
 * JPA.</p>
 */
@Entity
@Table(name = "promemoria")
@SequenceGenerator(name = "seq_promemoria", sequenceName = "seq_promemoria", allocationSize = 1)
public class Promemoria {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_promemoria")
    @Column(name = "id")
    private Long id;

    @Column(name = "id_versamento")
    private Long idVersamento;

    @Column(name = "id_rpt")
    private Long idRpt;

    @Column(name = "tipo", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private TipoPromemoria tipo;

    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

    @Column(name = "stato", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private StatoSpedizionePromemoria stato;

    @Column(name = "descrizione_stato", length = 1024)
    private String descrizioneStato;

    @Column(name = "destinatario_to", nullable = false, length = 256)
    private String destinatarioTo;

    @Column(name = "destinatario_cc", length = 256)
    private String destinatarioCc;

    @Column(name = "allega_pdf", nullable = false)
    private boolean allegaPdf;

    @Column(name = "data_aggiornamento_stato", nullable = false)
    private OffsetDateTime dataAggiornamentoStato;

    @Column(name = "data_prossima_spedizione", nullable = false)
    private OffsetDateTime dataProssimaSpedizione;

    @Column(name = "tentativi_spedizione")
    private Long tentativiSpedizione;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIdVersamento() {
        return idVersamento;
    }

    public void setIdVersamento(Long idVersamento) {
        this.idVersamento = idVersamento;
    }

    public Long getIdRpt() {
        return idRpt;
    }

    public void setIdRpt(Long idRpt) {
        this.idRpt = idRpt;
    }

    public TipoPromemoria getTipo() {
        return tipo;
    }

    public void setTipo(TipoPromemoria tipo) {
        this.tipo = tipo;
    }

    public OffsetDateTime getDataCreazione() {
        return dataCreazione;
    }

    public void setDataCreazione(OffsetDateTime dataCreazione) {
        this.dataCreazione = dataCreazione;
    }

    public StatoSpedizionePromemoria getStato() {
        return stato;
    }

    public void setStato(StatoSpedizionePromemoria stato) {
        this.stato = stato;
    }

    public String getDescrizioneStato() {
        return descrizioneStato;
    }

    public void setDescrizioneStato(String descrizioneStato) {
        this.descrizioneStato = descrizioneStato;
    }

    public String getDestinatarioTo() {
        return destinatarioTo;
    }

    public void setDestinatarioTo(String destinatarioTo) {
        this.destinatarioTo = destinatarioTo;
    }

    public String getDestinatarioCc() {
        return destinatarioCc;
    }

    public void setDestinatarioCc(String destinatarioCc) {
        this.destinatarioCc = destinatarioCc;
    }

    public boolean isAllegaPdf() {
        return allegaPdf;
    }

    public void setAllegaPdf(boolean allegaPdf) {
        this.allegaPdf = allegaPdf;
    }

    public OffsetDateTime getDataAggiornamentoStato() {
        return dataAggiornamentoStato;
    }

    public void setDataAggiornamentoStato(OffsetDateTime dataAggiornamentoStato) {
        this.dataAggiornamentoStato = dataAggiornamentoStato;
    }

    public OffsetDateTime getDataProssimaSpedizione() {
        return dataProssimaSpedizione;
    }

    public void setDataProssimaSpedizione(OffsetDateTime dataProssimaSpedizione) {
        this.dataProssimaSpedizione = dataProssimaSpedizione;
    }

    public Long getTentativiSpedizione() {
        return tentativiSpedizione;
    }

    public void setTentativiSpedizione(Long tentativiSpedizione) {
        this.tentativiSpedizione = tentativiSpedizione;
    }
}
