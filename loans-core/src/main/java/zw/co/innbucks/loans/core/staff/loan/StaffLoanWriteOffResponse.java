package zw.co.innbucks.loans.core.staff.loan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A write-off request and where it stands, with the loan it is for.
 *
 * @param amount     what the loan owed when it was proposed, as loans knows it
 * @param loanStatus the loan's status now
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffLoanWriteOffResponse(
        Long id,
        Long staffLoanId,
        String loanReference,
        String employeeNumber,
        String fullName,
        StaffLoanStatus loanStatus,
        StaffLoanWriteOffKind kind,
        BigDecimal amount,
        String currency,
        String reason,
        StaffLoanWriteOffStatus status,
        String proposedBy,
        LocalDateTime proposedAt,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionComment) {

    static StaffLoanWriteOffResponse of(StaffLoanWriteOff request, StaffLoan loan) {
        return new StaffLoanWriteOffResponse(request.getId(), loan.getId(), loan.getReference(),
                loan.getEmployeeNumber(), loan.getFullName(), loan.getStatus(), request.getKind(),
                request.getAmount(), request.getCurrency(), request.getReason(), request.getStatus(),
                request.getProposedBy(), request.getProposedAt(), request.getDecidedBy(), request.getDecidedAt(),
                request.getDecisionComment());
    }
}
