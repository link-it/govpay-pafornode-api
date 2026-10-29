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

import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;

/**
 * Notifica push AppIO in coda di spedizione (es. ricevuta di pagamento), mappata sulla
 * tabella legacy {@code notifiche_app_io} — riuso diretto.
 *
 * <p><b>Solo i campi scritti all'accodamento</b>: {@code id_messaggio}/
 * {@code stato_messaggio} restano {@code NULL} qui — valorizzati dal batch di spedizione
 * (non ancora portato, fuori perimetro) con la risposta dell'API AppIO, non
 * all'accodamento. Stesso principio di {@link Promemoria}.</p>
 *
 * <p>Fuori dall'aggregato {@link PosizioneDebitoria}: {@link #idVersamento}/
 * {@link #idRpt} sono FK piatte, non relazioni JPA. {@link #idTipoVersamentoDominio} e'
 * una FK piatta verso l'anagrafica esterna di govpay-common (M4, stesso principio di
 * {@link Pendenza#getIdDominio()}).</p>
 */
@Entity
@Table(name = "notifiche_app_io")
@SequenceGenerator(name = "seq_notifiche_app_io", sequenceName = "seq_notifiche_app_io", allocationSize = 1)
public class NotificaAppIo {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_notifiche_app_io")
    @Column(name = "id")
    private Long id;

    @Column(name = "id_versamento", nullable = false)
    private Long idVersamento;

    @Column(name = "id_tipo_versamento_dominio", nullable = false)
    private Long idTipoVersamentoDominio;

    @Column(name = "id_rpt")
    private Long idRpt;

    @Column(name = "debitore_identificativo", nullable = false, length = 35)
    private String debitoreIdentificativo;

    @Column(name = "cod_versamento_ente", nullable = false, length = 35)
    private String codVersamentoEnte;

    @Column(name = "cod_applicazione", nullable = false, length = 35)
    private String codApplicazione;

    @Column(name = "cod_dominio", nullable = false, length = 35)
    private String codDominio;

    @Column(name = "iuv", nullable = false, length = 35)
    private String iuv;

    /** Colonna fisica {@code tipo_esito} (nomenclatura legacy, non {@code tipo}). */
    @Column(name = "tipo_esito", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private TipoNotificaAppIo tipo;

    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

    @Column(name = "stato", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private StatoSpedizioneNotificaAppIo stato;

    @Column(name = "descrizione_stato", length = 255)
    private String descrizioneStato;

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

    public Long getIdTipoVersamentoDominio() {
        return idTipoVersamentoDominio;
    }

    public void setIdTipoVersamentoDominio(Long idTipoVersamentoDominio) {
        this.idTipoVersamentoDominio = idTipoVersamentoDominio;
    }

    public Long getIdRpt() {
        return idRpt;
    }

    public void setIdRpt(Long idRpt) {
        this.idRpt = idRpt;
    }

    public String getDebitoreIdentificativo() {
        return debitoreIdentificativo;
    }

    public void setDebitoreIdentificativo(String debitoreIdentificativo) {
        this.debitoreIdentificativo = debitoreIdentificativo;
    }

    public String getCodVersamentoEnte() {
        return codVersamentoEnte;
    }

    public void setCodVersamentoEnte(String codVersamentoEnte) {
        this.codVersamentoEnte = codVersamentoEnte;
    }

    public String getCodApplicazione() {
        return codApplicazione;
    }

    public void setCodApplicazione(String codApplicazione) {
        this.codApplicazione = codApplicazione;
    }

    public String getCodDominio() {
        return codDominio;
    }

    public void setCodDominio(String codDominio) {
        this.codDominio = codDominio;
    }

    public String getIuv() {
        return iuv;
    }

    public void setIuv(String iuv) {
        this.iuv = iuv;
    }

    public TipoNotificaAppIo getTipo() {
        return tipo;
    }

    public void setTipo(TipoNotificaAppIo tipo) {
        this.tipo = tipo;
    }

    public OffsetDateTime getDataCreazione() {
        return dataCreazione;
    }

    public void setDataCreazione(OffsetDateTime dataCreazione) {
        this.dataCreazione = dataCreazione;
    }

    public StatoSpedizioneNotificaAppIo getStato() {
        return stato;
    }

    public void setStato(StatoSpedizioneNotificaAppIo stato) {
        this.stato = stato;
    }

    public String getDescrizioneStato() {
        return descrizioneStato;
    }

    public void setDescrizioneStato(String descrizioneStato) {
        this.descrizioneStato = descrizioneStato;
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
