package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.MsisdnUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A Staff Grocery Loan as Credit, Finance and Human Capital see it. The borrower's number is masked.
 * {@code employmentFlag} is set on a paid-out loan whose borrower is no longer ACTIVE on the register, and
 * {@code arrearsOverrideId} on one accepted under Credit's arrears override (FR-SGL-014).
 */
public record StaffLoanResponse(Long id, String reference, Long offerId, Long staffMemberId, String employeeNumber,
                                String fullName, String msisdn, String grade, String merchantCode,
                                String merchantName, BigDecimal amount, String currency,
                                BigDecimal interestRate, BigDecimal totalRepayable, LocalDate dueDate,
                                UnredeemedVoucherTreatment unredeemedVoucherTreatment, StaffLoanStatus status,
                                boolean inArrears, LocalDateTime acceptedAt, LocalDateTime disbursedAt,
                                String disbursementReference, LocalDateTime settledAt, LocalDateTime cancelledAt,
                                String cancelledBy, String cancellationReason,
                                StaffLoanEmploymentFlag employmentFlag, Long arrearsOverrideId) {

    static StaffLoanResponse of(StaffLoan loan, LocalDate today, int graceDays) {
        return new StaffLoanResponse(loan.getId(), loan.getReference(), loan.getOfferId(), loan.getStaffMemberId(),
                loan.getEmployeeNumber(), loan.getFullName(), MsisdnUtils.mask(loan.getMsisdn()), loan.getGrade(),
                loan.getMerchant().getMerchantCode(), loan.getMerchant().getCompanyName(), loan.getAmount(),
                loan.getCurrency(), loan.getInterestRate(), loan.getTotalRepayable(),
                loan.getDueDate(), loan.getUnredeemedVoucherTreatment(), loan.getStatus(),
                loan.inArrearsOn(today, graceDays), loan.getAcceptedAt(), loan.getDisbursedAt(),
                loan.getDisbursementReference(), loan.getSettledAt(), loan.getCancelledAt(), loan.getCancelledBy(),
                loan.getCancellationReason(), StaffLoanEmploymentFlag.of(loan), loan.getArrearsOverrideId());
    }
}
