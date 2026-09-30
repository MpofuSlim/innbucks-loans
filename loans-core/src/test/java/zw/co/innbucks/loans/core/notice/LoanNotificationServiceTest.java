package zw.co.innbucks.loans.core.notice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.LoanStage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The applicant is told of a stage only once it is committed, and telling them can never fail the stage
 * (FR-SSB-016).
 */
class LoanNotificationServiceTest {

    private static final String MOBILE = "+263771234567";

    private LoanNotificationSender sender;
    private LoanNotificationRepository notificationRepository;
    private LoanRepository loanRepository;
    private LoanNotificationService service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        sender = mock(LoanNotificationSender.class);
        notificationRepository = mock(LoanNotificationRepository.class);
        loanRepository = mock(LoanRepository.class);
        service = new LoanNotificationService(sender, notificationRepository, loanRepository);
        loan = Loan.builder().mobileNumber(MOBILE).disbursedAmount(new BigDecimal("319.15")).build();
        loan.setId(43L);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private OutgoingNotice delivered() {
        ArgumentCaptor<OutgoingNotice> captor = ArgumentCaptor.forClass(OutgoingNotice.class);
        verify(sender).deliver(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("outside a transaction the notice goes at once, in its own words, under a fresh gateway reference")
    void outsideATransactionItIsSentAtOnce() {
        service.notify(loan, LoanNotice.SENT_TO_SSB);

        OutgoingNotice sent = delivered();
        assertThat(sent.loanId()).isEqualTo(43L);
        assertThat(sent.notice()).isEqualTo(LoanNotice.SENT_TO_SSB);
        assertThat(sent.recipient()).isEqualTo(MOBILE);
        assertThat(sent.message()).isEqualTo(LoanNotice.SENT_TO_SSB.textFor(loan));
        assertThat(sent.gatewayReference()).startsWith("LOANS-SMS-").hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("inside a transaction nothing is sent until it commits")
    void insideATransactionItWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.notify(loan, LoanNotice.APPROVED);
        verifyNoInteractions(sender);

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(delivered().notice()).isEqualTo(LoanNotice.APPROVED);
    }

    @Test
    @DisplayName("a stage that is rolled back is never announced")
    void aRolledBackStageIsNeverAnnounced() {
        TransactionSynchronizationManager.initSynchronization();

        service.notify(loan, LoanNotice.APPROVED);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("the words and number are those of the moment it was raised, not of the commit")
    void theNoticeIsCapturedWhenRaised() {
        TransactionSynchronizationManager.initSynchronization();

        service.notify(loan, LoanNotice.PAID, "Your loan of 319.15 has been paid");
        loan.setMobileNumber("+263772000000");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        OutgoingNotice sent = delivered();
        assertThat(sent.recipient()).isEqualTo(MOBILE);
        assertThat(sent.message()).isEqualTo("Your loan of 319.15 has been paid");
    }

    @Test
    @DisplayName("a notice with no wording of its own is refused quietly, not thrown into the caller")
    void aNoticeThatCannotBeWordedIsDropped() {
        assertThatCode(() -> service.notify(loan, LoanNotice.PAID)).doesNotThrowAnyException();

        verify(sender, never()).deliver(any());
    }

    @Test
    @DisplayName("an executor that refuses the send does not reach the caller, before or after the commit")
    void aRefusedSendDoesNotReachTheCaller() {
        doThrow(new TaskRejectedException("executor shut down")).when(sender).deliver(any());

        assertThatCode(() -> service.notify(loan, LoanNotice.DECLINED)).doesNotThrowAnyException();

        TransactionSynchronizationManager.initSynchronization();
        service.notify(loan, LoanNotice.DECLINED);
        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("history lists what the applicant was sent, oldest first, with the stage each told them")
    void historyListsWhatWasSent() {
        when(loanRepository.existsById(43L)).thenReturn(true);
        LocalDateTime at = LocalDateTime.of(2026, 9, 30, 8, 15);
        when(notificationRepository.findByLoanIdOrderByIdAsc(43L)).thenReturn(List.of(
                LoanNotification.builder().id(1L).loanId(43L).notice(LoanNotice.RECEIVED).channel("SMS")
                        .recipient(MOBILE).message("received").gatewayReference("LOANS-SMS-1").sent(true)
                        .attemptedAt(at).build(),
                LoanNotification.builder().id(2L).loanId(43L).notice(LoanNotice.SSB_CONFIRMED).channel("SMS")
                        .recipient(MOBILE).message("confirmed").gatewayReference("LOANS-SMS-2").sent(false)
                        .failureReason("InnBucks gateway rejected SMS: HTTP 503").attemptedAt(at.plusDays(1)).build()));

        List<LoanNotificationResponse> history = service.history(43L, LoanReadScope.platform());

        assertThat(history).extracting(LoanNotificationResponse::notice)
                .containsExactly(LoanNotice.RECEIVED, LoanNotice.SSB_CONFIRMED);
        assertThat(history).extracting(LoanNotificationResponse::stage)
                .containsExactly(LoanStage.RECEIVED, LoanStage.WITH_CREDIT);
        assertThat(history.get(1).sent()).isFalse();
        assertThat(history.get(1).failureReason()).isEqualTo("InnBucks gateway rejected SMS: HTTP 503");
    }

    @Test
    @DisplayName("an originator reads only their own loans' history; anything else is not found")
    @SuppressWarnings("unchecked")
    void historyIsScopedToWhatTheCallerMayRead() {
        LoanReadScope agent = LoanReadScope.originator("harare-motors", 7L);
        when(loanRepository.exists(any(Specification.class))).thenReturn(false);

        assertThatThrownBy(() -> service.history(43L, agent))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 43 not found");
        verifyNoInteractions(notificationRepository);
        verify(loanRepository, never()).existsById(any());
    }

    @Test
    @DisplayName("a loan that does not exist is not found, even for the platform")
    void historyOfAMissingLoanIsNotFound() {
        when(loanRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.history(99L, LoanReadScope.platform()))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(notificationRepository);
    }
}
