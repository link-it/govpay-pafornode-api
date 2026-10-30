package it.govpay.pafornode.security;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import it.govpay.common.entity.IntermediarioEntity;

/**
 * Query aggiuntiva su {@link IntermediarioEntity} per il lookup ad uso
 * autenticazione (per {@code principal}, non per {@code codIntermediario}
 * come {@code it.govpay.common.repository.IntermediarioRepository}, che non
 * la espone). Interfaccia separata sulla stessa entity, non una modifica a
 * quella di govpay-common: la libreria non e' editabile da qui.
 */
@Repository
public interface IntermediarioPrincipalRepository extends JpaRepository<IntermediarioEntity, Long> {

	Optional<IntermediarioEntity> findByPrincipal(String principal);
}
