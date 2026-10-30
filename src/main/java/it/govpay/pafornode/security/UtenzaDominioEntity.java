package it.govpay.pafornode.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Mappa la tabella condivisa {@code utenze_domini} (autorizzazione esplicita di un'utenza a
 * un dominio, o a una sua unita' operativa, quando {@code utenze.autorizzazione_domini_star}
 * e' false) — copia locale a questo servizio, stesso schema di {@code
 * it.govpay.pendenze.security.UtenzaDominioEntity} in govpay-pendenze-api (vedi Javadoc di
 * {@link UtenzaEntity} sul perche' non e' condivisa). Una riga ha {@code idDominio} oppure
 * {@code idUo} (mai entrambi): quando e' una UO, il dominio autorizzato e' quello del padre
 * ({@code UnitaOperativaEntity#getDominio()} in govpay-common). FK piatte (M4): nessuna
 * relazione JPA.
 */
@Entity
@Table(name = "utenze_domini")
@SequenceGenerator(name = "seq_utenze_domini", sequenceName = "seq_utenze_domini", allocationSize = 1)
public class UtenzaDominioEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_utenze_domini")
	@Column(name = "id")
	private Long id;

	@Column(name = "id_utenza", nullable = false)
	private Long idUtenza;

	@Column(name = "id_dominio")
	private Long idDominio;

	@Column(name = "id_uo")
	private Long idUo;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getIdUtenza() {
		return idUtenza;
	}

	public void setIdUtenza(Long idUtenza) {
		this.idUtenza = idUtenza;
	}

	public Long getIdDominio() {
		return idDominio;
	}

	public void setIdDominio(Long idDominio) {
		this.idDominio = idDominio;
	}

	public Long getIdUo() {
		return idUo;
	}

	public void setIdUo(Long idUo) {
		this.idUo = idUo;
	}
}
