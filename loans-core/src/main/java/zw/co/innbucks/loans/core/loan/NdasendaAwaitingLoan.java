package zw.co.innbucks.loans.core.loan;

import java.time.LocalDateTime;

/**
 * A loan whose deduction reached Ndasenda and is still waiting for its answer, as the few columns the
 * response job needs to date it — read without the loan's documents and images, which every run
 * would otherwise load for every waiting loan.
 */
public record NdasendaAwaitingLoan(Long id,
                                   LoanApprovalStatus loanApprovalStatus,
                                   String batchNumber,
                                   String ecNumber,
                                   LocalDateTime dateApproved,
                                   LocalDateTime createdDate,
                                   LocalDateTime responseOverdueAt) {

    /**
     * When the deduction was lodged. {@code dateApproved} is misnamed: NdasendaLodgementJob stamps
     * it at the moment Ndasenda accepts the lodgement (together with the batch number), and only an
     * applied answer overwrites it — which a loan still waiting has not had. The batch number is
     * Ndasenda's opaque id and dates nothing. {@code createdDate} is the application, which is never
     * later than the lodgement, so as a fallback it can only make the job read further back, never
     * skip a day.
     */
    public LocalDateTime lodgedAt() {
        return dateApproved != null ? dateApproved : createdDate;
    }
}
