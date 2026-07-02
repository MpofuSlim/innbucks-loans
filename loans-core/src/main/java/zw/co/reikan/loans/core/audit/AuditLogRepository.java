package zw.co.reikan.loans.core.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByCorrelationIdOrderByIdAsc(String correlationId);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByIdAsc(String entityType, String entityId);
}
