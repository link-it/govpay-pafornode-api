package it.govpay.pafornode.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pafornode.Application;

/**
 * Verifica che il wiring con il metodo SSL abilitato faccia partire il contesto
 * senza bean mancanti — es. {@code PasswordEncoder}/{@code UserDetailsService}/
 * {@code X509} richiesti da {@code GovpaySecurityFilterChainAutoConfiguration}.
 * Profilo dedicato (non {@code test}, che abilita invece il metodo HEADER —
 * vedi {@code application-test.properties} — per i test HTTP di business logic).
 *
 * <p>Non verifica una richiesta autenticata con un certificato client reale
 * (mTLS) — deliberatamente fuori scope per ora: il confronto principal/
 * idBrokerPA e' comunque esercitato per davvero, con un metodo piu' semplice
 * da simulare via HTTP, da {@code PaForNodeEndpointTest}.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("authwiring")
class PaForNodeAuthWiringTest {

	@Test
	void ilContestoPartesConLaCatenaDiSicurezzaAttiva() {
		// Il solo avvio senza eccezioni e' l'asserzione: nessun bean mancante
		// nella catena govpay-common-auth con il metodo SSL abilitato.
	}
}
