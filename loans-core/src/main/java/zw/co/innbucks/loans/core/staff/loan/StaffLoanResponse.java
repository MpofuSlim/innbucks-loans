package zw.co.innbucks.loans.core.staff.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.MsisdnUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A Staff Grocery Loan as Credit, Finance and Human Capital see it. The borrower's number is masked.
 * {@code employmentFlag} is set on a paid-out loan whose borrower is no longer ACTIVE on the register, and
 * {@code arrearsOverrideId} on one accepted under Credit's arrears override (FR-SGL-014).
 *
 * <p>A loan paid in CASH says so ({@code payoutMethod}), and carries where it is paid ({@code payoutDestination}, and
 * {@code payoutAccount} by its last four digits), where its payout stands ({@code payoutStatus}) and why
 * ({@code payoutMessage}), and what the bank said of it: its answer to the booking ({@code payoutApplyAnswer}) and its
 * last answer ({@code payoutLastAnswer}), both redacted (ID numbers, account numbers and phone numbers masked), its
 * status ({@code payoutBankStatus}), when it first said it holds it ({@code payoutBankSeenAt}), reported it missing
 * ({@code payoutNotFoundAt}) or held it as declined ({@code payoutDeclinedAt}), when its inquiry last answered
 * ({@code payoutCheckedAt}), and its own loan account ({@code bankLoanAccount}, by its last four characters). A payout a
 * person confirmed was never paid has {@code payoutFailedAt}, and {@code payoutPaidAfterCancelAt} if the bank was found
 * to hold it after all.
 *
 * <p>Each payout field is left out when it is empty, and a voucher loan carries none of them, not even
 * {@code payoutMethod}: a voucher loan reads exactly as it did before cash payouts existed. The merchant is the one
 * recorded at acceptance; a cash loan does not use it.
 */
public record StaffLoanResponse(Long id, String reference, Long offerId, Long staffMemberId, String employeeNumber,
                                String fullName, String msisdn, String grade, String merchantCode,
                                String merchantName, BigDecimal amount, String currency,
                                BigDecimal interestRate, BigDecimal totalRepayable, LocalDate dueDate,
                                UnredeemedVoucherTreatment unredeemedVoucherTreatment, StaffLoanStatus status,
                                boolean inArrears, LocalDateTime acceptedAt, LocalDateTime disbursedAt,
                                String disbursementReference, LocalDateTime settledAt, LocalDateTime cancelledAt,
                                String cancelledBy, String cancellationReason,
                                StaffLoanEmploymentFlag employmentFlag, Long arrearsOverrideId,
                                @JsonInclude(JsonInclude.Include.NON_NULL) StaffLoanPayoutMethod payoutMethod,
                                @JsonInclude(JsonInclude.Include.NON_NULL) StaffLoanPayoutDestination payoutDestination,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String payoutAccount,
                                @JsonInclude(JsonInclude.Include.NON_NULL) StaffLoanPayoutStatus payoutStatus,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String payoutMessage,
                                @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime payoutClaimedAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String payoutBankStatus,
                                @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime payoutBankSeenAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime payoutNotFoundAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime payoutDeclinedAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime payoutCheckedAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String payoutApplyAnswer,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String payoutLastAnswer,
                                @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime payoutFailedAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime payoutPaidAfterCancelAt,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String bankLoanAccount) {

    /** Reads its merchant: called inside the transaction that loaded it. */
    static StaffLoanResponse of(StaffLoan loan, LocalDate today, int graceDays) {
        boolean cash = loan.getPayoutMethod() == StaffLoanPayoutMethod.CASH;
        return new StaffLoanResponse(loan.getId(), loan.getReference(), loan.getOfferId(), loan.getStaffMemberId(),
                loan.getEmployeeNumber(), loan.getFullName(), MsisdnUtils.mask(loan.getMsisdn()), loan.getGrade(),
                loan.getMerchant().getMerchantCode(), loan.getMerchant().getCompanyName(), loan.getAmount(),
                loan.getCurrency(), loan.getInterestRate(), loan.getTotalRepayable(),
                loan.getDueDate(), loan.getUnredeemedVoucherTreatment(), loan.getStatus(),
                loan.inArrearsOn(today, graceDays), loan.getAcceptedAt(), loan.getDisbursedAt(),
                loan.getDisbursementReference(), loan.getSettledAt(), loan.getCancelledAt(), loan.getCancelledBy(),
                loan.getCancellationReason(), StaffLoanEmploymentFlag.of(loan), loan.getArrearsOverrideId(),
                cash ? StaffLoanPayoutMethod.CASH : null, loan.getPayoutDestination(),
                MsisdnUtils.mask(loan.getPayoutAccount()), loan.getPayoutStatus(), loan.getPayoutMessage(),
                loan.getPayoutClaimedAt(), loan.getPayoutBankStatus(), loan.getPayoutBankSeenAt(),
                loan.getPayoutNotFoundAt(), loan.getPayoutDeclinedAt(), loan.getPayoutCheckedAt(),
                loan.getPayoutApplyAnswer(), loan.getPayoutLastAnswer(), loan.getPayoutFailedAt(),
                loan.getPayoutPaidAfterCancelAt(), maskLoanAccount(loan.getBankLoanAccount()));
    }

    /**
     * The bank's loan account as {@code ****0245}: an account number is never shown in full. Our reference, which the
     * bank holds too, is what a payment is traced by.
     */
    static String maskLoanAccount(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        String kept = account.strip();
        return "****" + (kept.length() <= 4 ? kept : kept.substring(kept.length() - 4));
    }
}
