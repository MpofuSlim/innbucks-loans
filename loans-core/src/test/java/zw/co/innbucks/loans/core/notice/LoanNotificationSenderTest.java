package zw.co.innbucks.loans.core.notice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.notifications.NotificationDeliveryException;
import zw.co.innbucks.loans.core.notifications.SmsNotificationClient;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every notice attempt is recorded, sent or not, and nothing a failed send or a failed record does may reach
 * the stage the notice is about (FR-SSB-016).
 */
class LoanNotificationSenderTest {

    private static final String MOBILE = "+263771234567";
    private static final String TEXT = "Your loan application with ref # 000000043 has been received.";

    private SmsNotificationClient smsClient;
    private LoanNotificationRepository repository;
    private LoanNotificationSender sender;

    @BeforeEach
    void setUp() {
        smsClient = mock(SmsNotificationClient.class);
        repository = mock(LoanNotificationRepository.class);
        sender = new LoanNotificationSender(smsClient, repository);
    }

    private static OutgoingNotice notice(String recipient) {
        return new OutgoingNotice(43L, LoanNotice.RECEIVED, recipient, TEXT, "LOANS-SMS-7f3a");
    }

    private LoanNotification recorded() {
        ArgumentCaptor<LoanNotification> captor = ArgumentCaptor.forClass(LoanNotification.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a notice the gateway takes is sent under its own reference and recorded as sent")
    void sentNoticeIsRecorded() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);

        sender.deliver(notice(MOBILE));

        verify(smsClient).sendSms(MOBILE, TEXT, "LOANS-SMS-7f3a");
        LoanNotification record = recorded();
        assertThat(record.getLoanId()).isEqualTo(43L);
        assertThat(record.getNotice()).isEqualTo(LoanNotice.RECEIVED);
        assertThat(record.getChannel()).isEqualTo(LoanNotification.SMS);
        assertThat(record.getRecipient()).isEqualTo(MOBILE);
        assertThat(record.getMessage()).isEqualTo(TEXT);
        assertThat(record.getGatewayReference()).isEqualTo("LOANS-SMS-7f3a");
        assertThat(record.isSent()).isTrue();
        assertThat(record.getFailureReason()).isNull();
        assertThat(record.getAttemptedAt()).isAfter(before);
    }

    @Test
    @DisplayName("a notice the gateway refuses is recorded as not sent, with the reason, and nothing is thrown")
    void refusedNoticeIsRecordedWithTheReason() {
        doThrow(new NotificationDeliveryException("InnBucks gateway rejected SMS: HTTP 400"))
                .when(smsClient).sendSms(any(), any(), any());

        assertThatCode(() -> sender.deliver(notice(MOBILE))).doesNotThrowAnyException();

        LoanNotification record = recorded();
        assertThat(record.isSent()).isFalse();
        assertThat(record.getFailureReason()).isEqualTo("InnBucks gateway rejected SMS: HTTP 400");
        assertThat(record.getMessage()).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("a failure with no message is recorded by its type, and a long one is cut to the column")
    void failureReasonAlwaysFitsTheColumn() {
        doThrow(new IllegalStateException()).when(smsClient).sendSms(any(), any(), any());
        sender.deliver(notice(MOBILE));
        assertThat(recorded().getFailureReason()).isEqualTo("IllegalStateException");

        LoanNotificationRepository second = mock(LoanNotificationRepository.class);
        doThrow(new NotificationDeliveryException("x".repeat(400))).when(smsClient).sendSms(any(), any(), any());
        new LoanNotificationSender(smsClient, second).deliver(notice(MOBILE));
        ArgumentCaptor<LoanNotification> captor = ArgumentCaptor.forClass(LoanNotification.class);
        verify(second).save(captor.capture());
        assertThat(captor.getValue().getFailureReason()).hasSize(255);
    }

    @Test
    @DisplayName("a loan with no mobile number sends nothing and records why")
    void noMobileNumberIsRecordedNotSent() {
        sender.deliver(notice(" "));

        verifyNoInteractions(smsClient);
        LoanNotification record = recorded();
        assertThat(record.isSent()).isFalse();
        assertThat(record.getFailureReason()).isEqualTo("The loan has no mobile number");
    }

    @Test
    @DisplayName("a notice that cannot be recorded still does not throw: the message has already gone")
    void recordFailureDoesNotThrow() {
        when(repository.save(any())).thenThrow(new IllegalStateException("connection refused"));

        assertThatCode(() -> sender.deliver(notice(MOBILE))).doesNotThrowAnyException();
        verify(smsClient).sendSms(MOBILE, TEXT, "LOANS-SMS-7f3a");
    }
}
