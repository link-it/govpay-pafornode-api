package it.govpay.pafornode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.ConfigurazioneEntity;
import it.govpay.common.entity.ConnettoreEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.DominioLogoEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.IntermediarioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.entity.TipoTributoEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.pafornode.security.UtenzaDominioEntity;
import it.govpay.pafornode.security.UtenzaEntity;

/**
 * Repository scan esteso a tutto {@code it.govpay.common.repository} (nessuna esclusione, a
 * differenza di {@code GovPayPendenzeApiApplication}): questo servizio usa
 * {@link it.govpay.common.repository.DominioRepository} (verifica dominio),
 * {@link it.govpay.common.repository.IntermediarioRepository}/
 * {@link it.govpay.common.repository.StazioneRepository} (verifica intermediario/stazione
 * chiamante), {@link it.govpay.common.repository.ConfigurazioneRepository}/
 * {@link it.govpay.common.repository.ConnettoreEntityRepository} (prerequisiti dei log
 * dinamici e delle chiamate al GDE). {@code basePackageClasses} su {@code @EnableJpaRepositories}
 * scansiona per PACKAGE del tipo indicato, non il singolo tipo: non esiste un modo per
 * includere solo alcuni repository di un package senza includerli tutti (verificato: il
 * tentativo con {@code basePackageClasses} su singoli repository attivava comunque l'intero
 * package). Hibernate valida ogni repository del package scansionato all'avvio, quindi tutte
 * le entity di {@code it.govpay.common.entity} (tranne {@code .batch}, mai usata) sono in
 * {@link #persistenceManagedTypes} — stesso principio di {@code GovPayPendenzeApiApplication}
 * ("entita' mai lette da nessun repository attivo qui servono comunque").
 *
 * <p>{@code @ComponentScan} esplicito su {@code it.govpay.pendenze}: questa classe vive in
 * {@code it.govpay.pafornode}, non in {@code it.govpay.pendenze} come
 * {@code GovPayPendenzeApiApplication} — senza di esso i {@code @Service}/{@code @Component}
 * di govpay-common-pendenze (es. {@code GeneratoreProgressivoIuv}, non un'autoconfigurazione)
 * non verrebbero scoperti.</p>
 *
 * <p>{@code it.govpay.pafornode.security} aggiunto a {@code @EnableJpaRepositories}:
 * {@link it.govpay.pafornode.security.IntermediarioPrincipalRepository}, usato da
 * {@link it.govpay.pafornode.security.PaForNodePrincipalLoader} (SPI di
 * {@code govpay-common-auth}) per il lookup dell'intermediario per principal —
 * query non esposta da {@code IntermediarioRepository} di govpay-common. Stesso package
 * ospita anche {@code UtenzaEntity}/{@code UtenzaDominioEntity} (copia locale delle tabelle
 * condivise {@code utenze}/{@code utenze_domini}, non un'anagrafica di govpay-common — vedi
 * Javadoc di classe di {@code UtenzaEntity}), usate da {@code ApplicazioneDominioResolver}
 * per risolvere quale applicazione gestisce un dominio/iuv quando non c'e' ancora una
 * pendenza (§3quinquies del design doc) — per questo elencate anche in
 * {@link #persistenceManagedTypes}.</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = { "it.govpay.pafornode", "it.govpay.pendenze" })
@EnableJpaRepositories(basePackages = { "it.govpay.pendenze.repository", "it.govpay.common.repository",
		"it.govpay.pafornode.security" })
public class Application extends SpringBootServletInitializer {

	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
		return application.sources(Application.class);
	}

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	/**
	 * Sostituisce {@code @EntityScan(basePackages = {"it.govpay.pendenze.entity",
	 * "it.govpay.common.entity"})}: quest'ultimo trascinerebbe dentro anche
	 * {@code it.govpay.common.entity.batch} (entity Spring Batch, mai usata da questo
	 * servizio, ma che Hibernate pretenderebbe comunque di validare contro lo schema) — stesso
	 * problema gia' risolto con questa tecnica in {@code GovPayPendenzeApiApplication}. Le
	 * entity di {@code it.govpay.pendenze.entity} (govpay-common-pendenze) sono scoperte
	 * dinamicamente (nessuna relazione JPA verso le anagrafiche di govpay-common: solo FK
	 * piatte, vedi Javadoc di classe di {@code PosizioneDebitoria}); le entity di
	 * {@code it.govpay.common.entity} sono invece elencate per nome, una per ciascun
	 * repository di {@code it.govpay.common.repository} (vedi Javadoc di classe).
	 */
	@Bean
	public PersistenceManagedTypes persistenceManagedTypes(ResourceLoader resourceLoader) {
		PersistenceManagedTypes proprie = new PersistenceManagedTypesScanner(resourceLoader)
				.scan("it.govpay.pendenze.entity");

		List<String> nomiClassi = new ArrayList<>(proprie.getManagedClassNames());
		nomiClassi.add(ApplicazioneEntity.class.getName());
		nomiClassi.add(DominioEntity.class.getName());
		nomiClassi.add(DominioLogoEntity.class.getName());
		nomiClassi.add(IntermediarioEntity.class.getName());
		nomiClassi.add(StazioneEntity.class.getName());
		nomiClassi.add(ConfigurazioneEntity.class.getName());
		nomiClassi.add(ConnettoreEntity.class.getName());
		nomiClassi.add(TipoVersamentoEntity.class.getName());
		nomiClassi.add(TipoVersamentoDominioEntity.class.getName());
		nomiClassi.add(UnitaOperativaEntity.class.getName());
		nomiClassi.add(TipoTributoEntity.class.getName());
		nomiClassi.add(TributoEntity.class.getName());
		nomiClassi.add(IbanAccreditoEntity.class.getName());
		nomiClassi.add(UtenzaEntity.class.getName());
		nomiClassi.add(UtenzaDominioEntity.class.getName());

		return PersistenceManagedTypes.of(nomiClassi, List.of("it.govpay.pendenze.entity"));
	}

	/** Orologio di sistema, iniettato dove serve "adesso" — sostituibile nei test. */
	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}
}
