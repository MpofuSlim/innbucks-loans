package zw.co.reikan.loans.core.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    boolean existsByTransactionRef(String transactionRef);

    List<LedgerEntry> findByLoanIdOrderByIdAsc(Long loanId);

    /**
     * Balance is a DERIVED value: signed sum over immutable history
     * (debits positive, credits negative — asset-account convention).
     */
    @Query("""
            select coalesce(sum(case when e.entryType = zw.co.reikan.loans.core.ledger.LedgerEntryType.DEBIT
                                     then e.amount else -e.amount end), 0)
            from LedgerEntry e where e.account = :account
            """)
    BigDecimal deriveAccountBalance(@Param("account") LedgerAccount account);

    @Query("""
            select coalesce(sum(case when e.entryType = zw.co.reikan.loans.core.ledger.LedgerEntryType.DEBIT
                                     then e.amount else -e.amount end), 0)
            from LedgerEntry e where e.account = :account and e.loanId = :loanId
            """)
    BigDecimal deriveLoanAccountBalance(@Param("account") LedgerAccount account, @Param("loanId") Long loanId);
}
