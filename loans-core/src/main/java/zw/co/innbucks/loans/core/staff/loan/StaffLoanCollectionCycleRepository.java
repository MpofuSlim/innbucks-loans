package zw.co.innbucks.loans.core.staff.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.time.LocalDate;
import java.util.Optional;

public interface StaffLoanCollectionCycleRepository
        extends JpaRepository<StaffLoanCollectionCycle, StaffLoanCollectionCycle.Key> {

    /** A payday's finalised cycle in one store. */
    default Optional<StaffLoanCollectionCycle> find(LocalDate payday, GetMoreMode storeMode) {
        return findById(new StaffLoanCollectionCycle.Key(payday, storeMode));
    }

    /** The first payday after {@code payday} already finalised in one store: none may be finalised before it. */
    Optional<StaffLoanCollectionCycle> findFirstByStoreModeAndPaydayAfterOrderByPaydayAsc(GetMoreMode storeMode,
                                                                                          LocalDate payday);
}
