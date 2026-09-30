package zw.co.innbucks.loans.core.notice;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.loan.LoanStage;

import java.time.LocalDateTime;

/**
 * A message the applicant was sent about their loan (FR-SSB-016).
 *
 * @param stage            the stage it told them the application had reached
 * @param message          the text as sent
 * @param gatewayReference the reference the SMS gateway holds it under, to trace it there
 * @param sent             the gateway accepted it; delivery to the handset is the gateway's to confirm
 * @param failureReason    why it was not sent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanNotificationResponse(
        LoanNotice notice,
        LoanStage stage,
        String channel,
        String recipient,
        String message,
        String gatewayReference,
        boolean sent,
        String failureReason,
        LocalDateTime attemptedAt) {

    static LoanNotificationResponse of(LoanNotification notification) {
        return new LoanNotificationResponse(notification.getNotice(), notification.getNotice().stage(),
                notification.getChannel(), notification.getRecipient(), notification.getMessage(),
                notification.getGatewayReference(), notification.isSent(), notification.getFailureReason(),
                notification.getAttemptedAt());
    }
}
