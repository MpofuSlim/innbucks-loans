package zw.co.innbucks.loans.core.loan;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an accepted application answers: the new loan and where it stands. A refused application
 * answers an error instead (409 APPLICATION_PENDING, 400 for a rule it breaks), never this with a
 * rejected status.
 *
 * @param id        the loan's id, for GET /loans/{loanId}
 * @param reference the loan reference quoted to SSB and InnBucks
 */
public record LoanApplicationResponse(
        @Schema(example = "42") Long id,
        @Schema(example = "000000042") String reference,
        @Schema(example = "NEW") LoanApprovalStatus ssbApprovalStatus) {
}
