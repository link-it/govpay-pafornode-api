package it.govpay.pafornode.verifica;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import it.govpay.common.entity.TipoTributoEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.TipoTributoRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.ec.client.beans.NuovaPendenza;
import it.govpay.ec.client.beans.NuovaVocePendenza;
import it.govpay.ec.client.beans.NuovoDocumento;
import it.govpay.ec.client.beans.PendenzaVerificata;
import it.govpay.ec.client.beans.Soggetto;
import it.govpay.pafornode.fault.FaultPa;
import it.govpay.pafornode.fault.NdpException;
import it.govpay.pendenze.comando.AggiornamentoPendenza;
import it.govpay.pendenze.comando.DatiOpzionePagamento;
import it.govpay.pendenze.comando.DatiPendenza;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.comando.DatiSoggettoDebitore;
import it.govpay.pendenze.comando.DatiVocePendenza;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Converte la {@code NuovaPendenza} riacquisita via "Verifica" (govpay-ec-client) in un
 * comando di aggiornamento per una pendenza GIA' ESISTENTE (§3quinquies del design doc).
 *
 * <p><b>Non e' il mapper del wrapper v2</b> ({@code NuovaPendenzaMapper} di
 * govpay-pendenze-api): quella classe dipende dal proprio modello generato e dalle entita'
 * di sicurezza di quel modulo (utenze), nessuna delle quali si applica qui — l'acquisizione
 * e' avviata da GovPay stesso tramite un connettore configurato, non da un chiamante HTTP da
 * autorizzare.
 *
 * <p><b>identita'/beneficiario non si aggiornano</b>: {@code idTipoPendenza}/
 * {@code idTipoVersamento}/{@code codificaIuvTipoPendenza} restano quelli della pendenza
 * esistente, non vengono ridichiarati dalla risposta (stesso principio di {@code
 * PosizioneDebitoriaService#aggiornaPendenza}).
 *
 * <p><b>Scope di questa prima versione</b>: una voce e' mappata solo se bollo
 * ({@code tipoBollo}+{@code hashDocumento}+{@code provinciaResidenza}) o se ha {@code
 * codEntrata} (tributo censito) — il ramo IBAN diretto del wrapper v2 non e' rappresentabile
 * in questo contratto (nessun campo {@code tipoContabilita}/{@code codiceContabilita} su
 * {@code NuovaVocePendenza}, a differenza del wrapper v2). {@code idDominio} per-voce (multi
 * ente) non e' supportato: una voce che lo dichiara fa fallire la mappatura, per non
 * cambiare silenziosamente il beneficiario. {@code datiAllegati}/{@code contabilita}/
 * {@code metadata} di voce, se popolati, fanno fallire la mappatura allo stesso modo.
 */
@Component
public class PendenzaVerificataMapper {

	private final TipoTributoRepository tipoTributoRepository;
	private final TributoRepository tributoRepository;
	private final TipoVersamentoDominioRepository tipoVersamentoDominioRepository;
	private final UnitaOperativaRepository unitaOperativaRepository;
	private final Clock clock;

	public PendenzaVerificataMapper(TipoTributoRepository tipoTributoRepository, TributoRepository tributoRepository,
			TipoVersamentoDominioRepository tipoVersamentoDominioRepository,
			UnitaOperativaRepository unitaOperativaRepository, Clock clock) {
		this.tipoTributoRepository = tipoTributoRepository;
		this.tributoRepository = tributoRepository;
		this.tipoVersamentoDominioRepository = tipoVersamentoDominioRepository;
		this.unitaOperativaRepository = unitaOperativaRepository;
		this.clock = clock;
	}

	public AggiornamentoPendenza toAggiornamentoPendenza(Pendenza pendenzaEsistente, NuovaPendenza dto,
			Long idDominio) {
		if (dto.getVoci() == null || dto.getVoci().isEmpty()) {
			throw new IllegalStateException("la pendenza riacquisita non indica 'voci'");
		}
		List<DatiVocePendenza> voci = new ArrayList<>();
		for (NuovaVocePendenza v : dto.getVoci()) {
			voci.add(toDatiVocePendenza(v, idDominio));
		}
		return new AggiornamentoPendenza(pendenzaEsistente.getIdPendenza(), pendenzaEsistente.getIdTipoPendenza(),
				pendenzaEsistente.getIdTipoVersamento(), pendenzaEsistente.getCodificaIuvTipoPendenza(),
				dto.getImporto().doubleValue(), dto.getNumeroAvviso(), aMezzanotte(dto.getDataValidita()),
				aMezzanotte(dto.getDataScadenza()), voci, dto.getCausale());
	}

	/**
	 * Converte la {@code NuovaPendenza} acquisita per la prima volta (pendenza DEL TUTTO
	 * ASSENTE, §3quinquies/punto aperto #10 del design doc) in un comando di CREAZIONE —
	 * a differenza di {@link #toAggiornamentoPendenza}, qui non esiste ancora nessuna
	 * pendenza/posizione da cui copiare {@code idTipoPendenza}/{@code idUnitaOperativa}: si
	 * risolvono dalla risposta stessa ({@code dto.getIdTipoPendenza()}/
	 * {@code dto.getIdUnitaOperativa()}), stesso principio di {@code
	 * NuovaPendenzaMapper#crea} del wrapper v2 (senza le verifiche di autorizzazione per
	 * utenza di quella classe, non applicabili qui). Una sola opzione di pagamento
	 * ({@code SOLUZIONE_UNICA}, nessuna rata/soglia: il contratto "Verifica" non porta
	 * questi concetti).
	 */
	public DatiPosizioneDebitoria creaDatiPosizioneDebitoria(NuovaPendenza dto, Long idApplicazione, Long idDominio) {
		if (dto.getVoci() == null || dto.getVoci().isEmpty()) {
			throw new IllegalStateException("la pendenza acquisita non indica 'voci'");
		}
		TipoVersamentoDominioEntity tvd = tipoVersamentoDominioRepository
				.findByCodTipoVersamentoAndDominioId(dto.getIdTipoPendenza(), idDominio)
				.orElseThrow(() -> new IllegalStateException(
						"nessun tipo pendenza [" + dto.getIdTipoPendenza() + "] configurato per il dominio [id:"
								+ idDominio + "]"));
		Long idUnitaOperativa = null;
		if (dto.getIdUnitaOperativa() != null) {
			UnitaOperativaEntity unitaOperativa = unitaOperativaRepository
					.findByCodUoAndDominioId(dto.getIdUnitaOperativa(), idDominio)
					.orElseThrow(() -> new IllegalStateException("nessuna unita' operativa ["
							+ dto.getIdUnitaOperativa() + "] per il dominio [id:" + idDominio + "]"));
			idUnitaOperativa = unitaOperativa.getId();
		}

		List<DatiVocePendenza> voci = new ArrayList<>();
		for (NuovaVocePendenza v : dto.getVoci()) {
			voci.add(toDatiVocePendenza(v, idDominio));
		}
		DatiPendenza datiPendenza = new DatiPendenza(dto.getIdPendenza(), tvd.getId(), tvd.getTipoVersamento().getId(),
				codificaIuvEffettiva(tvd), dto.getImporto().doubleValue(), dto.getNumeroAvviso(), null,
				aMezzanotte(dto.getDataValidita()), aMezzanotte(dto.getDataScadenza()), voci, dto.getCausale());
		DatiOpzionePagamento opzione = new DatiOpzionePagamento(TipologiaOpzionePagamento.SOLUZIONE_UNICA, null, null,
				null, List.of(datiPendenza));
		DatiSoggettoDebitore soggetto = toDatiSoggettoDebitore(dto.getSoggettoPagatore());

		NuovoDocumento documento = dto.getDocumento();
		String codDocumento = documento != null ? documento.getIdentificativo()
				: UUID.randomUUID().toString().replace("-", "");
		String descrizioneDocumento = documento != null ? documento.getDescrizione() : dto.getCausale();

		return new DatiPosizioneDebitoria(codDocumento, idApplicazione, idDominio, idUnitaOperativa,
				descrizioneDocumento, null, false, null, List.of(soggetto), List.of(opzione));
	}

	private static DatiSoggettoDebitore toDatiSoggettoDebitore(Soggetto dto) {
		return new DatiSoggettoDebitore(it.govpay.pendenze.model.TipoSoggetto.valueOf(dto.getTipo().name()),
				dto.getIdentificativo(), dto.getAnagrafica(), dto.getIndirizzo(), dto.getCivico(), dto.getCap(),
				dto.getLocalita(), dto.getProvincia(), dto.getNazione(), dto.getEmail(), dto.getCellulare());
	}

	private static String codificaIuvEffettiva(TipoVersamentoDominioEntity tvd) {
		return tvd.getCodificaIuv() != null ? tvd.getCodificaIuv() : tvd.getTipoVersamento().getCodificaIuv();
	}

	private DatiVocePendenza toDatiVocePendenza(NuovaVocePendenza v, Long idDominio) {
		if (v.getIdDominio() != null) {
			throw new IllegalStateException(
					"la voce [" + v.getIdVocePendenza() + "] indica 'idDominio': riacquisizione multi-ente non "
							+ "supportata, non si cambia silenziosamente il beneficiario");
		}
		if (v.getDatiAllegati() != null || contabilitaONonVuota(v) || metadataNonVuota(v)) {
			throw new IllegalStateException("la voce [" + v.getIdVocePendenza()
					+ "] indica 'datiAllegati'/'contabilita'/'metadata': nessun campo equivalente, non puo' essere "
					+ "persistito");
		}

		Long idTributo = null;
		String tipoBollo = null;
		String hashDocumento = null;
		String provinciaResidenza = null;

		if (v.getTipoBollo() != null && v.getHashDocumento() != null && v.getProvinciaResidenza() != null) {
			tipoBollo = v.getTipoBollo().getValue();
			hashDocumento = v.getHashDocumento();
			provinciaResidenza = v.getProvinciaResidenza();
		} else if (v.getCodEntrata() != null) {
			idTributo = risolviIdTributo(v.getCodEntrata(), idDominio);
		} else {
			throw new IllegalStateException("la voce [" + v.getIdVocePendenza() + "] non indica ne' un bollo "
					+ "(tipoBollo+hashDocumento+provinciaResidenza) ne' codEntrata");
		}

		return new DatiVocePendenza(v.getIdVocePendenza(), v.getImporto().doubleValue(), v.getDescrizione(),
				idDominio, idTributo, null, null, null, tipoBollo, hashDocumento, provinciaResidenza, List.of());
	}

	private static boolean contabilitaONonVuota(NuovaVocePendenza v) {
		return v.getContabilita() != null && (!v.getContabilita().getQuote().isEmpty()
				|| v.getContabilita().getProprietaCustom() != null);
	}

	private static boolean metadataNonVuota(NuovaVocePendenza v) {
		return v.getMetadata() != null && !v.getMetadata().getMapEntries().isEmpty();
	}

	private Long risolviIdTributo(String codEntrata, Long idDominio) {
		TipoTributoEntity tipoTributo = tipoTributoRepository.findByCodTributo(codEntrata)
				.orElseThrow(() -> new IllegalStateException("nessun tipo tributo con codEntrata [" + codEntrata
						+ "]"));
		TributoEntity tributo = tributoRepository.findByDominioIdAndTipoTributoId(idDominio, tipoTributo.getId())
				.orElseThrow(() -> new IllegalStateException("nessun tributo [" + codEntrata
						+ "] configurato per il dominio [id:" + idDominio + "]"));
		if (Boolean.FALSE.equals(tributo.getAbilitato())) {
			throw new IllegalStateException(
					"il tributo [" + codEntrata + "] per il dominio [id:" + idDominio + "] non e' abilitato");
		}
		return tributo.getId();
	}

	private OffsetDateTime aMezzanotte(LocalDate data) {
		return data == null ? null : data.atStartOfDay(clock.getZone()).toOffsetDateTime();
	}

	/**
	 * Eccezioni di business della risposta "Verifica" mappate sugli stessi {@code FaultPa}
	 * gia' usati da {@code PaForNodeChecks} — condiviso tra riacquisizione (pendenza trovata
	 * con validita' scaduta) e acquisizione (pendenza assente).
	 */
	public static void verificaEsito(PendenzaVerificata risposta, String codDominio) {
		switch (risposta.getStato()) {
			case ANNULLATA -> throw new NdpException(FaultPa.PAA_PAGAMENTO_ANNULLATO, codDominio);
			case SCADUTA -> throw new NdpException(FaultPa.PAA_PAGAMENTO_SCADUTO, codDominio);
			case SCONOSCIUTA -> throw new NdpException(FaultPa.PAA_PAGAMENTO_SCONOSCIUTO, codDominio);
			case NON_ESEGUITA -> {
				// procede: la pendenza (ri)acquisita e' valida.
			}
		}
	}
}
