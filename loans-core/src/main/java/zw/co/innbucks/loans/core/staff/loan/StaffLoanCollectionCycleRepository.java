package zw.co.innbucks.loans.core.staff.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Takes a transaction-scoped Postgres advisory lock on {@code key}, waiting while another transaction holds it, so
     * one store's finalisations run one at a time. Released when the CALLER'S transaction ends; it must be the first
     * statement, so everything read after it sees what the previous finalisation committed. Selected FROM the
     * function so the result is a plain integer, not Postgres' void.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    Integer lockStore(@Param("key") String key);
}
