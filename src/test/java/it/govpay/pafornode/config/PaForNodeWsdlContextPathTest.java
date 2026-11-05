package it.govpay.pafornode.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

/**
 * Issue #10, bug di revisione corretto 2026-11-05 (due giri): {@link
 * PaForNodeWsdlDefinitionHandlerAdapter} sostituiva il solo suffisso {@code /ws} del {@code
 * soap:address} con il path del canale interno, senza inserire il context path — invisibile
 * in tutti gli altri test di questo modulo, che deployano sempre alla radice ({@code
 * server.servlet.context-path} vuoto, il default). Il primo fix lo inseriva solo quando
 * correggeva verso il canale interno, lasciando il canale Nodo (nessuna sostituzione di
 * path, comportamento di default di Spring-WS su un location assoluto) ancora privo di
 * context path — qui si forza esplicitamente un context path non vuoto per provare che
 * NESSUNO dei due canali lo perde. Classe dedicata (non nello stesso {@code @SpringBootTest}
 * degli altri test del canale interno): la property {@code server.servlet.context-path} si
 * applica all'intero contesto, altrimenti invaliderebbe ogni altro URL hardcoded in quella
 * classe.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "server.servlet.context-path=/pafornode")
@ActiveProfiles("test")
class PaForNodeWsdlContextPathTest {

	@LocalServerPort
	private int port;

	private final RestTemplate restTemplate = new RestTemplate();

	@Test
	void wsdlDelCanaleInternoIncludeIlContextPathQuandoConfigurato() {
		ResponseEntity<String> risposta = restTemplate.getForEntity("http://localhost:" + port
				+ "/pafornode/internal/PagamentiTelematiciCCPservice?wsdl", String.class);

		assertThat(risposta.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(risposta.getBody()).contains("soap:address location=\"http://localhost:" + port
				+ "/pafornode/internal/PagamentiTelematiciCCPservice\"");
	}

	/**
	 * Anche il canale Nodo deve includere il context path — non solo quello interno: lo
	 * stesso difetto (Spring-WS non lo antepone per un location assoluto) vale per entrambi,
	 * corretto per entrambi.
	 */
	@Test
	void wsdlDelCanaleNodoIncludeAncheLuiIlContextPathQuandoConfigurato() {
		ResponseEntity<String> risposta = restTemplate
				.getForEntity("http://localhost:" + port + "/pafornode/ws?wsdl", String.class);

		assertThat(risposta.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(risposta.getBody())
				.contains("soap:address location=\"http://localhost:" + port + "/pafornode/ws\"");
	}
}
