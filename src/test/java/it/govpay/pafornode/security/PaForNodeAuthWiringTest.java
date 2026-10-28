package it.govpay.pafornode.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pafornode.Application;

/**
 * Verifica che il wiring "di produzione" dell'autenticazione (nessuna delle
 * esclusioni usate dal profilo {@code test}, un metodo {@code govpay.auth.*}
 * abilitato) faccia partire il contesto senza bean mancanti — es.
 * {@code PasswordEncoder}/{@code UserDetailsService}/{@code X509} richiesti
 * da {@code GovpaySecurityFilterChainAutoConfiguration} una volta che
 * {@link PaForNodePrincipalLoader} e' nel contesto (profilo diverso da
 * {@code test}, quindi {@code @Profile("!test")} non lo esclude qui).
 *
 * <p>Non verifica una richiesta autenticata per davvero (certificato client
 * reale) — deliberatamente fuori scope per ora, vedi Javadoc della classe
 * sotto test.
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
