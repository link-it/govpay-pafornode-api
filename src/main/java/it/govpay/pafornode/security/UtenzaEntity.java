package it.govpay.pafornode.security;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Mappa la tabella condivisa {@code utenze} — copia locale a questo servizio, stesso schema
 * di {@code it.govpay.pendenze.security.UtenzaEntity} in govpay-pendenze-api (e di
 * {@code it.govpay.console.entity.Utenza} in govpay-console-api): e' un concetto di
 * infrastruttura di sicurezza, non di anagrafica di dominio, quindi ogni consumer mappa la
 * propria vista sulla tabella condivisa — non esiste un'entity comune in govpay-common per
 * questo (deviazione consapevole rispetto ad {@code ApplicazioneEntity}/{@code DominioEntity},
 * che vivono li').
 *
 * <p>Usata da {@link ApplicazioneDominioResolver} (ricerca dell'applicazione che gestisce un
 * dominio/iuv, §3quinquies del design doc) e, da issue #10, da
 * {@link PaForNodePrincipalLoader} per l'autenticazione BASIC del canale interno
 * (govpay-rt-batch/govpay-console-api) — il canale Nodo (pagoPA) resta autenticato via
 * {@code IntermediarioEntity}, non tocca questa entity.</p>
 */
@Entity
@Table(name = "utenze")
@SequenceGenerator(name = "seq_utenze", sequenceName = "seq_utenze", allocationSize = 1)
public class UtenzaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_utenze")
	@Column(name = "id")
	private Long id;

	@Column(name = "autorizzazione_domini_star", nullable = false)
	private Boolean autorizzazioneDominiStar;

	// NOT NULL nello schema condiviso (credenziali dell'applicazione, mai scritte da
	// questo servizio, che legge solo): mappati esclusivamente per poter persistere righe
	// di fixture nei test di ApplicazioneDominioResolver.
	@Column(name = "principal", nullable = false, length = 4000, unique = true)
	private String principal = UUID.randomUUID().toString();

	@Column(name = "principal_originale", nullable = false, length = 4000)
	private String principalOriginale = "";

	@Column(name = "abilitato", nullable = false)
	private Boolean abilitato = true;

	@Column(name = "ruoli", length = 512)
	private String ruoli;

	@Column(name = "password", length = 255)
	private String password;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getPrincipal() {
		return principal;
	}

	public void setPrincipal(String principal) {
		this.principal = principal;
	}

	public String getPrincipalOriginale() {
		return principalOriginale;
	}

	public void setPrincipalOriginale(String principalOriginale) {
		this.principalOriginale = principalOriginale;
	}

	public Boolean getAutorizzazioneDominiStar() {
		return autorizzazioneDominiStar;
	}

	public void setAutorizzazioneDominiStar(Boolean autorizzazioneDominiStar) {
		this.autorizzazioneDominiStar = autorizzazioneDominiStar;
	}

	public Boolean getAbilitato() {
		return abilitato;
	}

	public void setAbilitato(Boolean abilitato) {
		this.abilitato = abilitato;
	}

	public String getRuoli() {
		return ruoli;
	}

	public void setRuoli(String ruoli) {
		this.ruoli = ruoli;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}
}
