package zw.co.innbucks.loans.core.staff.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverride;
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverrideStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An arrears override and where it stands.
 *
 * @param validUntil         the last market day the member may take a loan under it
 * @param staffLoanReference on a USED override: the loan the member took under it
 * @param inForce            on an APPROVED override only: whether it lets the member borrow today
 * @param notInForceReason   on an APPROVED override that is not in force: why
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffArrearsOverrideResponse(
        Long id,
        String employeeNumber,
        String fullName,
        String reason,
        LocalDate validUntil,
        StaffArrearsOverrideStatus status,
        String proposedBy,
        LocalDateTime proposedAt,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionComment,
        Long supersededBy,
        LocalDateTime supersededAt,
        String revokedBy,
        LocalDateTime revokedAt,
        String revocationReason,
        String staffLoanReference,
        LocalDateTime usedAt,
        Boolean inForce,
        String notInForceReason) {

    /**
     * @param loanReference the reference of the loan it was used for, when USED
     * @param today         today in the market's time zone
     */
    static StaffArrearsOverrideResponse of(StaffArrearsOverride override, StaffMember member, String loanReference,
                                           LocalDate today) {
        Boolean inForce = null;
        String notInForceReason = null;
        if (override.getStatus() == StaffArrearsOverrideStatus.APPROVED) {
            inForce = override.inForceOn(today);
            if (!inForce) {
                notInForceReason = "Its last day was " + override.getValidUntil();
            }
        }
        return new StaffArrearsOverrideResponse(override.getId(), member.getEmployeeNumber(), member.getFullName(),
                override.getReason(), override.getValidUntil(), override.getStatus(), override.getProposedBy(),
                override.getProposedAt(), override.getDecidedBy(), override.getDecidedAt(),
                override.getDecisionComment(), override.getSupersededBy(), override.getSupersededAt(),
                override.getRevokedBy(), override.getRevokedAt(), override.getRevocationReason(), loanReference,
                override.getUsedAt(), inForce, notInForceReason);
    }
}
