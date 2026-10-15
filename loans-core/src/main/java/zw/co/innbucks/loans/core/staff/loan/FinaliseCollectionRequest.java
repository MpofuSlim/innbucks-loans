package zw.co.innbucks.loans.core.staff.loan;

import jakarta.validation.constraints.Size;

/**
 * Finalising a payday's Staff Grocery Loan collections ({@code POST /staff-loan-collections/{payday}/finalisation}).
 * A payday with a line not final, or a voucher not read from GetMore since the cut-off, is finalised only with
 * {@code acceptNonFinal} and the reason (audited and kept on the cycle). Both optional otherwise; no body is the same
 * as {@code acceptNonFinal: false}.
 */
public record FinaliseCollectionRequest(
        Boolean acceptNonFinal,
        @Size(max = 255, message = "reason must be at most 255 characters") String reason) {

    /** Whether anything not final is accepted: false unless asked for. */
    public boolean acceptNonFinalAsked() {
        return Boolean.TRUE.equals(acceptNonFinal);
    }
}
