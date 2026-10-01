package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.MsisdnUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A Staff Grocery Loan as Credit, Finance and Human Capital see it. The borrower's number is masked. */
public record StaffLoanResponse(Long id, String reference, Long offerId, Long staffMemberId, String employeeNumber,
                                String fullName, String msisdn, String grade, BigDecimal amount, String currency,
                                BigDecimal interestRate, BigDecimal totalRepayable, LocalDate dueDate,
                                UnredeemedVoucherTreatment unredeemedVoucherTreatment, StaffLoanStatus status,
                                boolean inArrears, LocalDateTime acceptedAt, LocalDateTime disbursedAt,
                                String disbursementReference, LocalDateTime settledAt, LocalDateTime cancelledAt,
                                String cancelledBy, String cancellationReason) {

    static StaffLoanResponse of(StaffLoan loan, LocalDate today, int graceDays) {
        return new StaffLoanResponse(loan.getId(), loan.getReference(), loan.getOfferId(), loan.getStaffMemberId(),
                loan.getEmployeeNumber(), loan.getFullName(), MsisdnUtils.mask(loan.getMsisdn()), loan.getGrade(),
                loan.getAmount(), loan.getCurrency(), loan.getInterestRate(), loan.getTotalRepayable(),
                loan.getDueDate(), loan.getUnredeemedVoucherTreatment(), loan.getStatus(),
                loan.inArrearsOn(today, graceDays), loan.getAcceptedAt(), loan.getDisbursedAt(),
                loan.getDisbursementReference(), loan.getSettledAt(), loan.getCancelledAt(), loan.getCancelledBy(),
                loan.getCancellationReason());
    }
}
