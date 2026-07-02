package zw.co.reikan.loans.core.ledger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Append-only double-entry posting service.
 *
 * <p>Invariants enforced here (and mirrored by DB constraints):</p>
 * <ul>
 *   <li>Every posting is a balanced set — total debits == total credits.</li>
 *   <li>Amounts are strictly positive; direction lives in the entry type.</li>
 *   <li>Postings are idempotent per {@code transactionRef}: replaying a saga
 *       step or a retried API call cannot double-post (unique constraint).</li>
 *   <li>No UPDATE path exists. Corrections are new, reversing entries.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository repository;

    public record Leg(LedgerAccount account, LedgerEntryType type, BigDecimal amount) {}

    /**
     * Atomically posts a balanced multi-leg transaction.
     *
     * @return true if posted; false if {@code transactionRef} was already
     *         posted (idempotent replay — a no-op, never a double post).
     */
    @Transactional
    public boolean post(String transactionRef, Long loanId, String currency, String description,
                        String createdBy, List<Leg> legs) {
        validateBalanced(legs);

        if (repository.existsByTransactionRef(transactionRef)) {
            log.info("Ledger posting {} already exists — idempotent replay, skipping", transactionRef);
            return false;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            for (Leg leg : legs) {
                repository.save(LedgerEntry.builder()
                        .transactionRef(transactionRef)
                        .loanId(loanId)
                        .account(leg.account())
                        .entryType(leg.type())
                        .amount(leg.amount())
                        .currency(currency)
                        .description(description)
                        .createdBy(createdBy)
                        .createdAt(now)
                        .build());
            }
            repository.flush();
            log.info("Posted ledger transaction {} ({} legs) for loan {}", transactionRef, legs.size(), loanId);
            return true;
        } catch (DataIntegrityViolationException race) {
            // A concurrent worker posted the same transactionRef first — idempotent outcome.
            log.info("Ledger posting {} lost insert race — treating as already posted", transactionRef);
            return false;
        }
    }

    /** Convenience for the common two-leg posting. */
    @Transactional
    public boolean postDoubleEntry(String transactionRef, Long loanId, String currency, String description,
                                   String createdBy, LedgerAccount debitAccount, LedgerAccount creditAccount,
                                   BigDecimal amount) {
        return post(transactionRef, loanId, currency, description, createdBy, List.of(
                new Leg(debitAccount, LedgerEntryType.DEBIT, amount),
                new Leg(creditAccount, LedgerEntryType.CREDIT, amount)));
    }

    public BigDecimal accountBalance(LedgerAccount account) {
        return repository.deriveAccountBalance(account);
    }

    public BigDecimal loanAccountBalance(LedgerAccount account, Long loanId) {
        return repository.deriveLoanAccountBalance(account, loanId);
    }

    static void validateBalanced(List<Leg> legs) {
        if (legs == null || legs.size() < 2) {
            throw new IllegalArgumentException("A ledger posting requires at least a debit and a credit leg");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Leg leg : legs) {
            if (leg.amount() == null || leg.amount().signum() <= 0) {
                throw new IllegalArgumentException("Ledger amounts must be strictly positive: " + leg);
            }
            if (leg.type() == LedgerEntryType.DEBIT) {
                debits = debits.add(leg.amount());
            } else {
                credits = credits.add(leg.amount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException(
                    "Unbalanced posting: debits=" + debits + " credits=" + credits);
        }
    }
}
