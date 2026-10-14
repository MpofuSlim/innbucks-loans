package zw.co.innbucks.loans.core.voucher;

import java.time.LocalDateTime;

/**
 * Something a GetMore sync of a voucher could not apply ({@code blocking}) or applied with a doubt, as staff see it.
 * {@code clearedAt} is absent while it is open. Never holds a code.
 *
 * @param loanAccount the loan the voucher pays out, when the caller has it to hand
 */
public record VoucherSyncIssueResponse(Long id, Long voucherId, String loanAccount, VoucherSyncIssueKind kind,
                                       boolean blocking, String externalRef, String detail,
                                       LocalDateTime firstSeenAt, LocalDateTime lastSeenAt,
                                       LocalDateTime clearedAt) {

    public static VoucherSyncIssueResponse of(VoucherSyncIssue issue, String loanAccount) {
        return new VoucherSyncIssueResponse(issue.getId(), issue.getVoucherId(), loanAccount, issue.getKind(),
                issue.isBlocking(), issue.getExternalRef(), issue.getDetail(), issue.getFirstSeenAt(),
                issue.getLastSeenAt(), issue.getClearedAt());
    }
}
