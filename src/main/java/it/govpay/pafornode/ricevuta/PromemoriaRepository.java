package it.govpay.pafornode.ricevuta;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository Spring Data per {@link Promemoria}.
 */
public interface PromemoriaRepository extends JpaRepository<Promemoria, Long> {
}
