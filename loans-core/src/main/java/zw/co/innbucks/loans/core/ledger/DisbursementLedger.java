package zw.co.innbucks.loans.core.ledger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.DisbursementService;
import zw.co.innbucks.loans.core.ledger.LedgerService.Leg;
import zw.co.innbucks.loans.core.loan.Loan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The ledger postings for a loan's payout, in one place, so every path that records a loan as
 * paid posts the same thing under the same reference.
 *
 * <p><b>What is posted.</b> The customer owes the principal, the payout is the principal less the
 * admin fee withheld from it, and the fee is our income. So one balanced transaction:</p>
 * <pre>
 *   DR LOAN_PRINCIPAL_RECEIVABLE   principal
 *      CR DISBURSEMENT_CLEARING    payout
 *      CR FEE_INCOME               principal - payout   (only when positive)
 * </pre>
 * <p>It used to be a single pair at the payout, which carried the receivable at the net amount,
 * understated by the fee, and never recorded the fee at all.</p>
 *
 * <p><b>Under which reference.</b> One posting per rail that paid the loan: {@code DISB-<loan
 * reference>} for the InnBucks booking, as before, and {@code DISB-MD-<loan reference>} for a manual
 * recovery payout, whose deposit reference is {@code MD-<loan reference>}. A loan should only ever be
 * paid by one of them; if both ever paid it, the ledger shows two payouts, which is the truth. The
 * rail is read from the loan's disbursement reference, which the recovery payout's claim sets
 * before any money moves, so the writer and the saga's backstop always agree on the reference and
 * the backstop's replay is a no-op rather than a second posting.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementLedger {

    /** SSB civil-servant loans are USD-denominated. */
    private static final String CURRENCY = "USD";
    private static final String PREFIX = "DISB-";
    private static final String REVERSAL_PREFIX = "DISB-REV-";

    private final LedgerService ledgerService;
    private final LedgerEntryRepository ledgerEntryRepository;

    /** The reference this loan's payout is posted under. */
    public static String transactionRef(Loan loan) {
        String paidBy = loan.getDisbursementReference();
        return paidBy != null && paidBy.startsWith(DisbursementService.MANUAL_REFERENCE_PREFIX)
                ? PREFIX + paidBy
                : bookingTransactionRef(loan);
    }

    /** The reference a payout by the InnBucks booking is posted under. */
    public static String bookingTransactionRef(Loan loan) {
        return PREFIX + loan.getReference();
    }

    /**
     * Posts the payout of a loan that has just been recorded as paid. Joins the caller's
     * transaction, so the payout and its posting commit together; idempotent per reference.
     *
     * <p>It cannot fail on the loan's figures: the legs are built positive and balanced, and a
     * loan with no payout amount is skipped with a warning rather than refused. Only a database
     * fault can make it throw, and that would fail the caller's own save too.</p>
     *
     * @return true if posted now; false if already posted, or skipped
     */
    @Transactional
    public boolean recordPayout(Loan loan, String actor) {
        BigDecimal payout = loan.getDisbursedAmount();
        if (payout == null || payout.signum() <= 0) {
            log.warn("LEDGER NOT POSTED: loan {} [{}] is paid but has no positive payout amount ({})",
                    loan.getId(), loan.getReference(), payout);
            return false;
        }
        BigDecimal fee = fee(loan, payout);
        List<Leg> legs = new ArrayList<>();
        legs.add(new Leg(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerEntryType.DEBIT, payout.add(fee)));
        legs.add(new Leg(LedgerAccount.DISBURSEMENT_CLEARING, LedgerEntryType.CREDIT, payout));
        if (fee.signum() > 0) {
            legs.add(new Leg(LedgerAccount.FEE_INCOME, LedgerEntryType.CREDIT, fee));
        }
        String ref = transactionRef(loan);
        String paidBy = loan.getDisbursementReference();
        String rail = !ref.equals(bookingTransactionRef(loan)) ? "manual recovery payout " + paidBy
                : paidBy == null ? "InnBucks booking" : "InnBucks booking (reference " + paidBy + ")";
        return ledgerService.post(ref, loan.getId(), CURRENCY,
                "Loan payout by " + rail + (fee.signum() > 0 ? ", admin fee " + fee + " withheld" : ""),
                actor, legs);
    }

    /**
     * The fee withheld from the payout: the principal less the payout, which is exactly how the
     * payout was derived. The stored fee is only a cross-check, and a principal below the payout
     * is booked at the payout with no fee rather than as a negative one.
     */
    private static BigDecimal fee(Loan loan, BigDecimal payout) {
        BigDecimal stored = loan.getFeeAmount();
        if (loan.getPrincipal() == null) {
            return stored != null && stored.signum() > 0 ? stored : BigDecimal.ZERO;
        }
        BigDecimal fee = loan.getPrincipal().subtract(payout);
        if (fee.signum() < 0) {
            log.warn("Loan {} [{}]: principal {} is below its payout {}; receivable booked at the payout, no fee",
                    loan.getId(), loan.getReference(), loan.getPrincipal(), payout);
            return BigDecimal.ZERO;
        }
        if (stored != null && stored.compareTo(fee) != 0) {
            log.warn("Loan {} [{}]: stored admin fee {} differs from principal {} less payout {}; booking the latter",
                    loan.getId(), loan.getReference(), stored, loan.getPrincipal(), payout);
        }
        return fee;
    }

    /**
     * Reverses the InnBucks booking's posting, when there is one, leg for leg as it was actually
     * posted: a posting made before the fee was recorded is a plain pair, one made since has a fee
     * leg, and both come back to nothing. Joins the caller's transaction; idempotent.
     *
     * @return what happened, for the saga's audit trail
     */
    @Transactional
    public String reverseBookingPayout(Loan loan, String actor, String reason) {
        String ref = bookingTransactionRef(loan);
        List<LedgerEntry> posted = ledgerEntryRepository.findByTransactionRef(ref);
        if (posted.isEmpty()) {
            return "no ledger movement to reverse (funds never left clearing)";
        }
        String reversalRef = REVERSAL_PREFIX + loan.getReference();
        List<Leg> legs = posted.stream()
                .map(entry -> new Leg(entry.getAccount(), opposite(entry.getEntryType()), entry.getAmount()))
                .toList();
        boolean reversed = ledgerService.post(reversalRef, loan.getId(), CURRENCY, reason + " " + ref, actor, legs);
        return reversed ? "ledger reversed via " + reversalRef
                : "ledger reversal already posted (" + reversalRef + ")";
    }

    private static LedgerEntryType opposite(LedgerEntryType type) {
        return type == LedgerEntryType.DEBIT ? LedgerEntryType.CREDIT : LedgerEntryType.DEBIT;
    }
}
