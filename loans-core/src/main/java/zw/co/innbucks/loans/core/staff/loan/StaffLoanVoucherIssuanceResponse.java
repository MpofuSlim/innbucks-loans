package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.time.LocalDateTime;

/**
 * Issuing a Staff Grocery Loan's GetMore voucher, as Credit and Finance see it. {@code mintedCodes} is how many codes
 * GetMore returned that loans could not bind and keeps sealed for withdrawal: a count, never a code.
 */
public record StaffLoanVoucherIssuanceResponse(Long id, Long staffLoanId, String loanReference,
                                               StaffLoanVoucherIssuanceStatus status, GetMoreMode storeMode,
                                               String couponPrefix, int attempts, int generateCalls,
                                               int generateAllowance, int mintedCodes, String lastOutcome,
                                               String lastError, LocalDateTime nextAttemptAt, Long voucherId,
                                               LocalDateTime createdAt, LocalDateTime updatedAt) {

    static StaffLoanVoucherIssuanceResponse of(StaffLoanVoucherIssuance issuance, String loanReference) {
        return new StaffLoanVoucherIssuanceResponse(issuance.getId(), issuance.getStaffLoanId(), loanReference,
                issuance.getStatus(), issuance.getStoreMode(), issuance.getCouponPrefix(), issuance.getAttempts(),
                issuance.getGenerateCalls(), issuance.getGenerateAllowance(), issuance.mintedCodeCount(),
                issuance.getLastOutcome(), issuance.getLastError(), issuance.getNextAttemptAt(),
                issuance.getVoucherId(), issuance.getCreatedAt(), issuance.getUpdatedAt());
    }
}
