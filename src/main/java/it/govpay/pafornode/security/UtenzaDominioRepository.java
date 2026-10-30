package it.govpay.pafornode.security;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UtenzaDominioRepository extends JpaRepository<UtenzaDominioEntity, Long> {

	List<UtenzaDominioEntity> findByIdUtenza(Long idUtenza);
}
