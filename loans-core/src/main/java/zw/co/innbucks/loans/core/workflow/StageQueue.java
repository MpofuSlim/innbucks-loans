package zw.co.innbucks.loans.core.workflow;

import zw.co.innbucks.loans.core.loan.Loan;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Which loans wait at a stage, derived from the loans' own state rather than stored separately, so a queue can never
 * disagree with the control that holds the loan there. Each wait is identified by when it began.
 */
public interface StageQueue {

    /** The loans waiting at the stage now, oldest wait first. */
    List<Waiting> waiting();

    /** When this loan's current wait at the stage began; empty if it is not waiting there. */
    Optional<LocalDateTime> enteredAt(Loan loan);

    /** The waits that ended in the period, from {@code from} to {@code to} inclusive (UTC). */
    List<Visit> endedBetween(LocalDateTime from, LocalDateTime to);

    /** A loan waiting at the stage, and since when. */
    record Waiting(Loan loan, LocalDateTime enteredAt) {
    }

    /**
     * A wait that ended: how it ended ({@code outcome}, such as APPROVED or RELEASED) and when. {@code enteredAt} is
     * null when there is no record of the loan reaching the stage, so the wait cannot be timed.
     */
    record Visit(Long loanId, LocalDateTime enteredAt, LocalDateTime endedAt, String outcome) {
    }
}
