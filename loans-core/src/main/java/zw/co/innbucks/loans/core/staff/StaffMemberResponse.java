package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A staff member on the register, with whether they may borrow today (FR-SGL-005): ACTIVE, and a grade whose limit in
 * force today is more than zero.
 *
 * @param limit            the grade's limit in force today, absent when it has none
 * @param ineligibleReason why they may not borrow, absent when they may
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffMemberResponse(
        Long id,
        String employeeNumber,
        String fullName,
        String nationalId,
        String mobileNumber,
        String grade,
        String department,
        StaffEmploymentStatus employmentStatus,
        LocalDate engagementDate,
        String walletAccountNumber,
        LocalDateTime statusChangedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean eligible,
        String ineligibleReason,
        StaffGradeLimit limit) {

    static StaffMemberResponse of(StaffMember member, StaffGradeLimit limit) {
        String reason = ineligibleReason(member, limit);
        return new StaffMemberResponse(member.getId(), member.getEmployeeNumber(), member.getFullName(),
                member.getNationalId(), member.getMsisdn(), member.getGrade(), member.getDepartment(),
                member.getEmploymentStatus(), member.getEngagementDate(), member.getWalletAccountNumber(),
                member.getStatusChangedAt(), member.getCreatedAt(), member.getUpdatedAt(), reason == null, reason,
                limit);
    }

    /**
     * Why the member may not borrow, or null when they may (FR-SGL-005).
     *
     * @param limit their grade's limit in force today, null when it has none
     */
    public static String ineligibleReason(StaffMember member, StaffGradeLimit limit) {
        if (member.getEmploymentStatus() != StaffEmploymentStatus.ACTIVE) {
            return "Employment status is " + member.getEmploymentStatus() + "; only ACTIVE staff may borrow";
        }
        if (limit == null) {
            return "Grade " + member.getGrade() + " has no limit in force today";
        }
        if (!limit.lends()) {
            return "Grade " + member.getGrade() + "'s limit is 0, so it is not lent to";
        }
        return null;
    }
}
