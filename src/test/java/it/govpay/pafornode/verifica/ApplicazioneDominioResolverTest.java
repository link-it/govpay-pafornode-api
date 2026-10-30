package it.govpay.pafornode.verifica;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pafornode.Application;
import it.govpay.pafornode.security.UtenzaDominioEntity;
import it.govpay.pafornode.security.UtenzaDominioRepository;
import it.govpay.pafornode.security.UtenzaEntity;
import it.govpay.pafornode.security.UtenzaRepository;

/**
 * Analisi completa del legacy ({@code it.govpay.core.business.Applicazione
 * #getApplicazioneDominio}) nel design doc (§3quinquies, punto aperto #10): prima
 * applicazione, nell'ordine del repository, autorizzata al dominio (star o specifica) E con
 * {@code regExp} che fa match sullo IUV.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class ApplicazioneDominioResolverTest {

	private static final String COD_DOMINIO = "12345678901";
	private static final String IUV = "000000000000000001";

	@Autowired
	private ApplicazioneDominioResolver resolver;

	@Autowired
	private DominioRepository dominioRepository;

	@Autowired
	private ApplicazioneRepository applicazioneRepository;

	@Autowired
	private UtenzaRepository utenzaRepository;

	@Autowired
	private UtenzaDominioRepository utenzaDominioRepository;

	private Long idDominio;

	@BeforeEach
	void creaDominio() {
		DominioEntity dominio = dominioRepository.save(DominioEntity.builder().codDominio(COD_DOMINIO)
				.ragioneSociale("Comune di Test").abilitato(true).intermediato(false).scaricaFr(false).auxDigit(0)
				.build());
		idDominio = dominio.getId();
	}

	@AfterEach
	void pulisci() {
		applicazioneRepository.deleteAll();
		utenzaDominioRepository.deleteAll();
		utenzaRepository.deleteAll();
		dominioRepository.deleteAll();
	}

	private Long nuovaUtenza(boolean autorizzazioneDominiStar) {
		UtenzaEntity utenza = new UtenzaEntity();
		utenza.setAutorizzazioneDominiStar(autorizzazioneDominiStar);
		return utenzaRepository.save(utenza).getId();
	}

	private void applicazione(String codApplicazione, String regExp, Long idUtenza) {
		applicazioneRepository.save(ApplicazioneEntity.builder().codApplicazione(codApplicazione).autoIuv(true)
				.firmaRicevuta("N").trusted(false).regExp(regExp).idUtenza(idUtenza).build());
	}

	@Test
	void trovaLapplicazioneConRegexCheFaMatchEAutorizzazioneStar() {
		Long idUtenza = nuovaUtenza(true);
		applicazione("APP-STAR", "^0+1$", idUtenza);

		var trovata = resolver.risolvi(dominioRepository.findById(idDominio).orElseThrow(), IUV);

		assertThat(trovata).isPresent();
		assertThat(trovata.get().getCodApplicazione()).isEqualTo("APP-STAR");
	}

	@Test
	void trovaLapplicazioneConAutorizzazioneSpecificaSulDominio() {
		Long idUtenza = nuovaUtenza(false);
		UtenzaDominioEntity riga = new UtenzaDominioEntity();
		riga.setIdUtenza(idUtenza);
		riga.setIdDominio(idDominio);
		utenzaDominioRepository.save(riga);
		applicazione("APP-SPECIFICA", "^0+1$", idUtenza);

		var trovata = resolver.risolvi(dominioRepository.findById(idDominio).orElseThrow(), IUV);

		assertThat(trovata).isPresent();
		assertThat(trovata.get().getCodApplicazione()).isEqualTo("APP-SPECIFICA");
	}

	@Test
	void nonTrovaNullaSeAutorizzataMaLaRegexNonMatcha() {
		Long idUtenza = nuovaUtenza(true);
		applicazione("APP-REGEX-DIVERSA", "^9+$", idUtenza);

		var trovata = resolver.risolvi(dominioRepository.findById(idDominio).orElseThrow(), IUV);

		assertThat(trovata).isEmpty();
	}

	@Test
	void nonTrovaNullaSeLaRegexMatchaMaNonEAutorizzataAlDominio() {
		Long idUtenza = nuovaUtenza(false);
		applicazione("APP-NON-AUTORIZZATA", "^0+1$", idUtenza);

		var trovata = resolver.risolvi(dominioRepository.findById(idDominio).orElseThrow(), IUV);

		assertThat(trovata).isEmpty();
	}

	@Test
	void nonTrovaNullaSeLapplicazioneNonHaUnaRegexConfigurata() {
		Long idUtenza = nuovaUtenza(true);
		applicazione("APP-SENZA-REGEX", null, idUtenza);

		var trovata = resolver.risolvi(dominioRepository.findById(idDominio).orElseThrow(), IUV);

		assertThat(trovata).isEmpty();
	}

	@Test
	void unAltroDominioSullaRigaUtenzeDominiNonAutorizzaQuestoDominio() {
		Long idUtenza = nuovaUtenza(false);
		DominioEntity altroDominio = dominioRepository
				.save(DominioEntity.builder().codDominio("99999999999").ragioneSociale("Altro Comune")
						.abilitato(true).intermediato(false).scaricaFr(false).auxDigit(0).build());
		UtenzaDominioEntity riga = new UtenzaDominioEntity();
		riga.setIdUtenza(idUtenza);
		riga.setIdDominio(altroDominio.getId());
		utenzaDominioRepository.save(riga);
		applicazione("APP-ALTRO-DOMINIO", "^0+1$", idUtenza);

		var trovata = resolver.risolvi(dominioRepository.findById(idDominio).orElseThrow(), IUV);

		assertThat(trovata).isEmpty();
	}
}
