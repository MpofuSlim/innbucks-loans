package zw.co.innbucks.loans.core.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.notifications.SmsNotificationClient;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Sends a notice and records what happened (FR-SSB-016). Off the caller's thread, so a slow gateway holds up
 * no request and no job, and it never throws: the stage the notice is about has already happened.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoanNotificationSender {

    private static final int FAILURE_LENGTH = 255;

    private final SmsNotificationClient smsNotificationClient;
    private final LoanNotificationRepository notificationRepository;

    @Async
    public void deliver(OutgoingNotice notice) {
        LoanNotification.LoanNotificationBuilder record = LoanNotification.builder()
                .loanId(notice.loanId())
                .notice(notice.notice())
                .channel(LoanNotification.SMS)
                .recipient(notice.recipient())
                .message(notice.message())
                .gatewayReference(notice.gatewayReference())
                .attemptedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        if (StringUtils.isBlank(notice.recipient())) {
            record.sent(false).failureReason("The loan has no mobile number");
            log.warn("Loan {} has no mobile number: its {} notice was not sent", notice.loanId(), notice.notice());
        } else {
            try {
                smsNotificationClient.sendSms(notice.recipient(), notice.message(), notice.gatewayReference());
                record.sent(true);
                log.info("Loan {} {} notice sent to {} ({})", notice.loanId(), notice.notice(),
                        MsisdnUtils.mask(notice.recipient()), notice.gatewayReference());
            } catch (RuntimeException ex) {
                String reason = StringUtils.defaultIfBlank(ex.getMessage(), ex.getClass().getSimpleName());
                record.sent(false).failureReason(StringUtils.abbreviate(reason, FAILURE_LENGTH));
                log.error("Loan {} {} notice to {} was not sent ({}): {}", notice.loanId(), notice.notice(),
                        MsisdnUtils.mask(notice.recipient()), notice.gatewayReference(), reason);
            }
        }
        try {
            notificationRepository.save(record.build());
        } catch (RuntimeException ex) {
            log.error("Loan {} {} notice ({}) could not be recorded", notice.loanId(), notice.notice(),
                    notice.gatewayReference(), ex);
        }
    }
}
