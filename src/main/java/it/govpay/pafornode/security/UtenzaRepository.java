package it.govpay.pafornode.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UtenzaRepository extends JpaRepository<UtenzaEntity, Long> {
}
