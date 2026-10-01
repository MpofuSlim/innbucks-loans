package zw.co.innbucks.loans.core.staff.offer;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.staff.StaffMember;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A limit override and where it stands.
 *
 * @param grade            the grade it was set for; it applies only while the member holds it
 * @param amount           their limit instead of the grade's; 0 stops offers to them
 * @param inForce          on an APPROVED override only: whether it sets the member's offers now
 * @param notInForceReason on an APPROVED override that is not in force: why
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffLimitOverrideResponse(
        Long id,
        String employeeNumber,
        String fullName,
        String grade,
        BigDecimal amount,
        String reason,
        StaffLimitOverrideStatus status,
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
        Boolean inForce,
        String notInForceReason) {

    static StaffLimitOverrideResponse of(StaffLimitOverride override, StaffMember member) {
        Boolean inForce = null;
        String notInForceReason = null;
        if (override.getStatus() == StaffLimitOverrideStatus.APPROVED) {
            inForce = override.appliesTo(member);
            if (!inForce) {
                notInForceReason = String.format("Employee %s's grade is now %s; this override was set for %s",
                        member.getEmployeeNumber(), member.getGrade(), override.getGrade());
            }
        }
        return new StaffLimitOverrideResponse(override.getId(), member.getEmployeeNumber(), member.getFullName(),
                override.getGrade(), override.getAmount(), override.getReason(), override.getStatus(),
                override.getProposedBy(), override.getProposedAt(), override.getDecidedBy(), override.getDecidedAt(),
                override.getDecisionComment(), override.getSupersededBy(), override.getSupersededAt(),
                override.getRevokedBy(), override.getRevokedAt(), override.getRevocationReason(), inForce,
                notInForceReason);
    }
}
