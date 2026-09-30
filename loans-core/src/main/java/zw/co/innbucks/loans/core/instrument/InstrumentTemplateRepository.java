package zw.co.innbucks.loans.core.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InstrumentTemplateRepository extends JpaRepository<InstrumentTemplate, Long> {

    /** The version in force: the latest published. */
    Optional<InstrumentTemplate> findFirstByInstrumentTypeOrderByVersionDesc(InstrumentType instrumentType);

    Optional<InstrumentTemplate> findByInstrumentTypeAndVersion(InstrumentType instrumentType, int version);

    List<InstrumentTemplate> findByInstrumentTypeOrderByVersionDesc(InstrumentType instrumentType);

    @Query("select coalesce(max(t.version), 0) from InstrumentTemplate t where t.instrumentType = :instrumentType")
    int findLatestVersion(@Param("instrumentType") InstrumentType instrumentType);
}
