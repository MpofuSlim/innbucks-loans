package zw.co.reikan.loans.core.bulk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BulkIngestionRunRepository extends JpaRepository<BulkIngestionRun, Long> {

    Optional<BulkIngestionRun> findByReference(String reference);
}
