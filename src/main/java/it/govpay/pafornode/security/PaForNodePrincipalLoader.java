package it.govpay.pafornode.security;

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
 * <p>Qui il principal e' quello del certificato client (CN/subject, secondo
 * {@code govpay.auth.ssl.subject-principal-regex}) o del valore propagato
 * dal reverse proxy (SSL_HEADER) — mai una coppia utente/password, quindi
 * {@code passwordHash} e' sempre {@code null} (previsto dalla libreria per
 * questi {@link AuthType}). Nessun ruolo granted: la libreria si limita ad
 * autenticare la connessione, la verifica di {@code idBrokerPA}/{@code
 * idStation}/{@code idPA} dichiarati nel body SOAP resta — come nel legacy —
 * un controllo di business separato, gia' in {@code PaForNodeEndpoint}.
 *
 * <p>Registrato in tutti i profili, incluso {@code test}: il profilo di test
 * abilita il metodo HEADER (il piu' semplice da simulare via HTTP, un header
 * anziche' un certificato) proprio per esercitare per davvero la catena di
 * sicurezza e il confronto principal/idBrokerPA in {@code PaForNodeEndpoint},
 * invece di lasciare quel confronto senza autenticazione nei test.
 */
@Component
public class PaForNodePrincipalLoader implements GovpayPrincipalLoader {

	private final IntermediarioPrincipalRepository intermediarioPrincipalRepository;

	public PaForNodePrincipalLoader(IntermediarioPrincipalRepository intermediarioPrincipalRepository) {
		this.intermediarioPrincipalRepository = intermediarioPrincipalRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public AuthenticatedSubject load(String principal, AuthType authType) {
		IntermediarioEntity intermediario = intermediarioPrincipalRepository.findByPrincipal(principal).orElse(null);
		if (intermediario == null) {
			return null;
		}
		boolean enabled = Boolean.TRUE.equals(intermediario.getAbilitato());
		return new AuthenticatedSubject(intermediario.getPrincipal(), null, enabled, List.of());
	}
}
