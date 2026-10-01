package it.govpay.pafornode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * La dipendenza da govpay-common, introdotta per la tracciatura di transaction id
 * e correlation id (BP-LOG-3), porta con se' {@code spring-boot-starter-data-jpa}.
 * Finche' il modulo non ha una base dati configurata, le autoconfigurazioni
 * DataSource/JPA sono escluse: senza esclusione {@code DataSourceAutoConfiguration}
 * pretenderebbe una URL di connessione e l'applicazione non partirebbe.
 */
@SpringBootApplication(exclude = {
		DataSourceAutoConfiguration.class,
		DataSourceTransactionManagerAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class,
		DataJpaRepositoriesAutoConfiguration.class })
public class Application extends SpringBootServletInitializer {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}
}
