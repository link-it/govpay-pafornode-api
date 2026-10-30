package it.govpay.pafornode.verifica;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import it.govpay.common.auth.AutorizzazioneDominio;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pafornode.security.UtenzaDominioEntity;
import it.govpay.pafornode.security.UtenzaDominioRepository;
import it.govpay.pafornode.security.UtenzaEntity;
import it.govpay.pafornode.security.UtenzaRepository;

/**
 * Risolve quale applicazione gestisce un dominio/iuv quando non c'e' ancora una pendenza da
 * cui leggere {@code idApplicazione} (§3quinquies/punto aperto #10 del design doc), analisi
 * completa del legacy ({@code it.govpay.core.business.Applicazione#getApplicazioneDominio})
 * nel design doc: itera tutte le applicazioni registrate e ritorna la PRIMA, nell'ordine
 * dato dal repository, che e' autorizzata al dominio (stessa semantica di {@link
 * AutorizzazioneDominio}) E ha una {@code regExp} che fa match sullo IUV — nessun fallback
 * "tipo versamento di default", nessuna gestione di ambiguita' se piu' applicazioni
 * soddisfano entrambe le condizioni (vince la prima trovata, come nel legacy).
 */
@Component
public class ApplicazioneDominioResolver {

	private final ApplicazioneRepository applicazioneRepository;
	private final UtenzaRepository utenzaRepository;
	private final UtenzaDominioRepository utenzaDominioRepository;
	private final UnitaOperativaRepository unitaOperativaRepository;

	public ApplicazioneDominioResolver(ApplicazioneRepository applicazioneRepository,
			UtenzaRepository utenzaRepository, UtenzaDominioRepository utenzaDominioRepository,
			UnitaOperativaRepository unitaOperativaRepository) {
		this.applicazioneRepository = applicazioneRepository;
		this.utenzaRepository = utenzaRepository;
		this.utenzaDominioRepository = utenzaDominioRepository;
		this.unitaOperativaRepository = unitaOperativaRepository;
	}

	public Optional<ApplicazioneEntity> risolvi(DominioEntity dominio, String iuv) {
		for (ApplicazioneEntity applicazione : applicazioneRepository.findAll()) {
			if (applicazione.getRegExp() == null) {
				continue;
			}
			UtenzaEntity utenza = utenzaRepository.findById(applicazione.getIdUtenza()).orElse(null);
			if (utenza == null) {
				continue;
			}
			boolean autorizzata = AutorizzazioneDominio.autorizzato(
					Boolean.TRUE.equals(utenza.getAutorizzazioneDominiStar()),
					autorizzataSulDominio(utenza, dominio.getId()));
			if (autorizzata && Pattern.matches(applicazione.getRegExp(), iuv)) {
				return Optional.of(applicazione);
			}
		}
		return Optional.empty();
	}

	private boolean autorizzataSulDominio(UtenzaEntity utenza, Long idDominio) {
		for (UtenzaDominioEntity riga : utenzaDominioRepository.findByIdUtenza(utenza.getId())) {
			if (idDominio.equals(riga.getIdDominio())) {
				return true;
			}
			if (riga.getIdUo() != null) {
				Optional<UnitaOperativaEntity> uo = unitaOperativaRepository.findById(riga.getIdUo());
				if (uo.isPresent() && idDominio.equals(uo.get().getDominio().getId())) {
					return true;
				}
			}
		}
		return false;
	}
}
