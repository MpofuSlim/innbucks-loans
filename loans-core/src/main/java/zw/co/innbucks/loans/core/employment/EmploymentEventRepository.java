package zw.co.innbucks.loans.core.employment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface EmploymentEventRepository extends JpaRepository<EmploymentEvent, Long> {

    boolean existsByEcNumberAndEventTypeAndEffectiveDate(String ecNumber, EmploymentEventType eventType,
                                                         LocalDate effectiveDate);

    Page<EmploymentEvent> findByEcNumber(String ecNumber, Pageable pageable);

    List<EmploymentEvent> findByIdIn(List<Long> ids);
}
