package it.govpay.pafornode.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.govpay.common.auth.spi.AuthType;
import it.govpay.common.auth.spi.AuthenticatedSubject;
import it.govpay.common.entity.IntermediarioEntity;

/**
 * Unit test del solo adapter (SPI {@code GovpayPrincipalLoader} -> data
 * layer): la chain di Spring Security che lo consuma e' quella di
 * govpay-common-auth, gia' validata nei suoi test e in govpay-console-api —
 * non va riverificata qui (vedi Javadoc di {@link PaForNodePrincipalLoader}
 * sul perche' non e' registrato con profilo {@code test}).
 */
class PaForNodePrincipalLoaderTest {

	private final IntermediarioPrincipalRepository repository = mock(IntermediarioPrincipalRepository.class);
	private final PaForNodePrincipalLoader loader = new PaForNodePrincipalLoader(repository);

	@Test
	void restituisceNullSePrincipalSconosciuto() {
		when(repository.findByPrincipal("sconosciuto")).thenReturn(Optional.empty());

		assertThat(loader.load("sconosciuto", AuthType.SSL)).isNull();
	}

	@Test
	void restituisceSubjectAbilitatoPerIntermediarioAbilitato() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario("BROKER-TEST")
				.principal("CN=broker-test.example.it").abilitato(true).build();
		when(repository.findByPrincipal("CN=broker-test.example.it")).thenReturn(Optional.of(intermediario));

		AuthenticatedSubject subject = loader.load("CN=broker-test.example.it", AuthType.SSL);

		assertThat(subject).isNotNull();
		assertThat(subject.principal()).isEqualTo("CN=broker-test.example.it");
		assertThat(subject.passwordHash()).isNull();
		assertThat(subject.enabled()).isTrue();
		assertThat(subject.grantedRoles()).isEmpty();
	}

	@Test
	void restituisceSubjectNonAbilitatoPerIntermediarioDisabilitato() {
		IntermediarioEntity intermediario = IntermediarioEntity.builder().codIntermediario("BROKER-TEST")
				.principal("CN=broker-test.example.it").abilitato(false).build();
		when(repository.findByPrincipal("CN=broker-test.example.it")).thenReturn(Optional.of(intermediario));

		AuthenticatedSubject subject = loader.load("CN=broker-test.example.it", AuthType.SSL);

		assertThat(subject).isNotNull();
		assertThat(subject.enabled()).isFalse();
	}
}
