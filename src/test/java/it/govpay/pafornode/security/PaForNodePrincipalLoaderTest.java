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
	private final UtenzaRepository utenzaRepository = mock(UtenzaRepository.class);
	private final PaForNodePrincipalLoader loader = new PaForNodePrincipalLoader(repository, utenzaRepository);

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

	/**
	 * Issue #10: {@code AuthType.BASIC} devia su {@link UtenzaEntity}, non su
	 * {@code IntermediarioEntity} — i due data layer non si toccano a vicenda.
	 */
	@Test
	void restituisceNullSePrincipalBasicSconosciuto() {
		when(utenzaRepository.findByPrincipal("sconosciuta")).thenReturn(Optional.empty());

		assertThat(loader.load("sconosciuta", AuthType.BASIC)).isNull();
	}

	@Test
	void restituisceSubjectConRuoliEPasswordPerUtenzaBasicAbilitata() {
		UtenzaEntity utenza = new UtenzaEntity();
		utenza.setPrincipal("app-rt-batch");
		utenza.setPrincipalOriginale("app-rt-batch");
		utenza.setAbilitato(true);
		utenza.setRuoli("RUOLO_A, RUOLO_B");
		utenza.setPassword("hash-password");
		when(utenzaRepository.findByPrincipal("app-rt-batch")).thenReturn(Optional.of(utenza));

		AuthenticatedSubject subject = loader.load("app-rt-batch", AuthType.BASIC);

		assertThat(subject).isNotNull();
		assertThat(subject.principal()).isEqualTo("app-rt-batch");
		assertThat(subject.passwordHash()).isEqualTo("hash-password");
		assertThat(subject.enabled()).isTrue();
		assertThat(subject.grantedRoles()).containsExactly("RUOLO_A", "RUOLO_B");
	}

	@Test
	void restituisceSubjectNonAbilitatoPerUtenzaBasicDisabilitata() {
		UtenzaEntity utenza = new UtenzaEntity();
		utenza.setPrincipal("app-rt-batch");
		utenza.setPrincipalOriginale("app-rt-batch");
		utenza.setAbilitato(false);
		when(utenzaRepository.findByPrincipal("app-rt-batch")).thenReturn(Optional.of(utenza));

		AuthenticatedSubject subject = loader.load("app-rt-batch", AuthType.BASIC);

		assertThat(subject).isNotNull();
		assertThat(subject.enabled()).isFalse();
	}

	@Test
	void unPrincipalBasicNonInterrogaIlDataLayerDellIntermediario() {
		UtenzaEntity utenza = new UtenzaEntity();
		utenza.setPrincipal("app-rt-batch");
		utenza.setPrincipalOriginale("app-rt-batch");
		utenza.setAbilitato(true);
		when(utenzaRepository.findByPrincipal("app-rt-batch")).thenReturn(Optional.of(utenza));

		loader.load("app-rt-batch", AuthType.BASIC);

		org.mockito.Mockito.verifyNoInteractions(repository);
	}

	/**
	 * Bug di revisione corretto 2026-11-04 (finding #3): {@code AuthType.LDAP} deve deviare
	 * su {@link UtenzaEntity} come {@code BASIC} — prima finiva nel ramo intermediario, quindi
	 * abilitare {@code govpay.auth.ldap.enabled} per il canale interno non era operativo.
	 */
	@Test
	void ldapDevolveComeBasicSuUtenzaEntityNonSuIntermediarioEntity() {
		UtenzaEntity utenza = new UtenzaEntity();
		utenza.setPrincipal("app-console-api");
		utenza.setPrincipalOriginale("app-console-api");
		utenza.setAbilitato(true);
		when(utenzaRepository.findByPrincipal("app-console-api")).thenReturn(Optional.of(utenza));

		AuthenticatedSubject subject = loader.load("app-console-api", AuthType.LDAP);

		assertThat(subject).isNotNull();
		assertThat(subject.principal()).isEqualTo("app-console-api");
		org.mockito.Mockito.verifyNoInteractions(repository);
	}

	@Test
	void autenticaUtenzaApplicativaVeraSoloPerBasicELdap() {
		assertThat(PaForNodePrincipalLoader.autenticaUtenzaApplicativa(AuthType.BASIC)).isTrue();
		assertThat(PaForNodePrincipalLoader.autenticaUtenzaApplicativa(AuthType.LDAP)).isTrue();
		assertThat(PaForNodePrincipalLoader.autenticaUtenzaApplicativa(AuthType.SSL)).isFalse();
		assertThat(PaForNodePrincipalLoader.autenticaUtenzaApplicativa(AuthType.SSL_HEADER)).isFalse();
		assertThat(PaForNodePrincipalLoader.autenticaUtenzaApplicativa(AuthType.HEADER)).isFalse();
		assertThat(PaForNodePrincipalLoader.autenticaUtenzaApplicativa(null)).isFalse();
	}
}
