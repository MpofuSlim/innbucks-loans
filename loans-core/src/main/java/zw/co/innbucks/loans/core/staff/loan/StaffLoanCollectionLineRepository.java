package zw.co.innbucks.loans.core.staff.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.time.LocalDate;
import java.util.List;

public interface StaffLoanCollectionLineRepository extends JpaRepository<StaffLoanCollectionLine, Long> {

    /** A finalised payday's lines, in the order they were written. */
    List<StaffLoanCollectionLine> findByPaydayAndStoreModeOrderByIdAsc(LocalDate payday, GetMoreMode storeMode);
}
