package zw.co.innbucks.loans.core.getmore.simulator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * STAGING ONLY: a simulated voucher named by its loan or by its code, never both, for "expire it now" and the store's
 * view of it. The code goes in this body, never a URL.
 *
 * @param reason why, for the record (expiry); optional
 */
public record SimCodeRequest(
        @Size(max = 64, message = "loanReference must be at most 64 characters") String loanReference,
        @Size(max = 64, message = "code must be at most 64 characters") String code,
        @Size(max = 255, message = "reason must be at most 255 characters") String reason) {

    /** Exactly one of {@code loanReference} and {@code code}. */
    @JsonIgnore
    @AssertTrue(message = "give exactly one of loanReference and code")
    public boolean isLoanReferenceOrCode() {
        return SimTargets.exactlyOne(loanReference, code);
    }

    @Override
    public String toString() {
        return "SimCodeRequest[loanReference=" + loanReference + ", code=" + (code == null ? null : "(given)")
                + ", reason=" + reason + "]";
    }
}
