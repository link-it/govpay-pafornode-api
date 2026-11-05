package it.govpay.pafornode.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.auth.spi.AuthType;
import it.govpay.common.auth.spi.AuthenticatedSubject;
import it.govpay.common.auth.spi.GovpayPrincipalLoader;
import it.govpay.common.entity.IntermediarioEntity;

/**
 * Implementazione pafornode-api della SPI {@link GovpayPrincipalLoader}, stesso
 * schema di {@code ConsoleGovpayPrincipalLoader} (govpay-console-api): un solo
 * {@code @Component} che interroga il proprio data layer per {@code principal}.
 *
 * <p>Due canali, due data layer, stesso loader (la libreria ne accetta uno solo per
 * applicazione): il canale Nodo (pagoPA) autentica la connessione (certificato client,
 * {@code AuthType.SSL}, o header propagato da un proxy, {@code SSL_HEADER}/{@code HEADER})
 * contro {@code IntermediarioEntity} — mai una coppia utente/password, quindi
 * {@code passwordHash} resta sempre {@code null} e nessun ruolo granted (la verifica di
 * {@code idBrokerPA}/{@code idStation}/{@code idPA} dichiarati nel body SOAP resta un
 * controllo di business separato, gia' in {@code PaForNodeEndpoint}). Il canale interno
 * (issue #10, govpay-rt-batch/govpay-console-api) autentica invece una coppia utente/password
 * reale ({@code AuthType.BASIC}) contro {@link UtenzaEntity} (tabella condivisa {@code
 * utenze}), stesso pattern di {@code PendenzeGovpayPrincipalLoader}/
 * {@code ConsoleGovpayPrincipalLoader} — la libreria gestisce da sola il confronto della
 * password (hash SHA-512 crypt).
 *
 * <p>I due rami convivono senza conflitto nella stessa security chain: ciascun metodo di
 * autenticazione scatta solo se la richiesta presenta IL SUO tipo di credenziale (header
 * Basic vs certificato/header SSL), quindi una richiesta Nodo non ha mai credenziali Basic e
 * viceversa. Questo loader autentica solo l'IDENTITA' (chi e', se abilitata): che quell'identita'
 * sia ammessa SUL PATH su cui e' arrivata (canale Nodo vs interno) e, per il canale interno,
 * che l'utenza sia davvero un'utenza applicativa (associata a una riga in {@code applicazioni},
 * non un'utenza operatore qualsiasi dello stesso schema condiviso {@code utenze}) sono
 * controlli di AUTORIZZAZIONE successivi, in {@code PaSendRTService#verificaCanale} — bug di
 * revisione corretto 2026-11-04: senza quei controlli, Basic funzionava anche sul path Nodo
 * (e viceversa) e un'utenza abilitata ma non applicativa arrivava comunque all'acquisizione RT
 * sul canale interno.
 *
 * <p>Registrato in tutti i profili, incluso {@code test}: il profilo di test abilita anche il
 * metodo HEADER sul canale Nodo (il piu' semplice da simulare via HTTP, un header anziche' un
 * certificato) proprio per esercitare per davvero la catena di sicurezza e il confronto
 * principal/idBrokerPA in {@code PaForNodeEndpoint}, invece di lasciare quel confronto senza
 * autenticazione nei test.
 */
@Component
public class PaForNodePrincipalLoader implements GovpayPrincipalLoader {

	private final IntermediarioPrincipalRepository intermediarioPrincipalRepository;
	private final UtenzaRepository utenzaRepository;

	public PaForNodePrincipalLoader(IntermediarioPrincipalRepository intermediarioPrincipalRepository,
			UtenzaRepository utenzaRepository) {
		this.intermediarioPrincipalRepository = intermediarioPrincipalRepository;
		this.utenzaRepository = utenzaRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public AuthenticatedSubject load(String principal, AuthType authType) {
		if (autenticaUtenzaApplicativa(authType)) {
			return loadUtenza(principal);
		}
		return loadIntermediario(principal);
	}

	/**
	 * {@code true} per gli {@link AuthType} che autenticano un'utenza applicativa (canale
	 * interno, {@link UtenzaEntity}) invece dell'intermediario pagoPA (canale Nodo, {@code
	 * IntermediarioEntity}) — {@code BASIC} (password nella tabella {@code utenze}) e {@code
	 * LDAP} (directory esterna, stessa identita' applicativa risolta allo stesso modo una
	 * volta autenticata: la libreria autentica la password, qui si cerca solo il principal).
	 * Bug di revisione corretto 2026-11-04: prima gestiva solo {@code BASIC}, quindi abilitare
	 * {@code govpay.auth.ldap.enabled} per il canale interno non era operativo — l'identita'
	 * finiva cercata fra gli intermediari. Pubblico: riusato da {@code
	 * PaSendRTService#verificaCanale} per decidere se il path su cui e' arrivata la richiesta
	 * corrisponde al metodo che l'ha autenticata.
	 */
	public static boolean autenticaUtenzaApplicativa(AuthType authType) {
		return authType == AuthType.BASIC || authType == AuthType.LDAP;
	}

	private AuthenticatedSubject loadIntermediario(String principal) {
		IntermediarioEntity intermediario = intermediarioPrincipalRepository.findByPrincipal(principal).orElse(null);
		if (intermediario == null) {
			return null;
		}
		boolean enabled = Boolean.TRUE.equals(intermediario.getAbilitato());
		return new AuthenticatedSubject(intermediario.getPrincipal(), null, enabled, List.of());
	}

	private AuthenticatedSubject loadUtenza(String principal) {
		UtenzaEntity utenza = utenzaRepository.findByPrincipal(principal).orElse(null);
		if (utenza == null) {
			return null;
		}
		boolean enabled = Boolean.TRUE.equals(utenza.getAbilitato());
		return new AuthenticatedSubject(utenza.getPrincipal(), utenza.getPassword(), enabled,
				parseRoles(utenza.getRuoli()));
	}

	private static List<String> parseRoles(String ruoliCsv) {
		if (ruoliCsv == null || ruoliCsv.isBlank()) {
			return List.of();
		}
		return Arrays.stream(ruoliCsv.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
	}
}
