package zw.co.innbucks.loans.core.turnaround;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecision;
import zw.co.innbucks.loans.core.loan.CreditDecisionRepository;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundReportResponse.ActionTurnaround;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Credit's turnaround: escalation once per wait, and the adherence report (FR-SSB-015 / FR-PBL-030). */
class CreditTurnaroundServiceTest {

    /** 10:00 on 30 September in Harare. */
    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");

    private ServiceLevelService serviceLevelService;
    private LoanRepository loanRepository;
    private CreditDecisionRepository creditDecisionRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private AuditService auditService;
    private CreditTurnaroundService service;

    @BeforeEach
    void setUp() {
        serviceLevelService = mock(ServiceLevelService.class);
        loanRepository = mock(LoanRepository.class);
        creditDecisionRepository = mock(CreditDecisionRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        when(serviceLevelService.serviceLevel(ServiceLevelStage.CREDIT_DECISION)).thenReturn(ServiceLevel.builder()
                .stage(ServiceLevelStage.CREDIT_DECISION).targetHours(24).escalationHours(48)
                .updatedBy("system").updatedAt(LocalDateTime.of(2026, 9, 1, 0, 0)).build());
        service = new CreditTurnaroundService(serviceLevelService, loanRepository, creditDecisionRepository,
                userRepository, notificationService, auditService,
                new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), mock(PlatformTransactionManager.class));
    }

    private static Loan awaiting(long id, LocalDateTime reachedCredit) {
        Loan loan = new Loan();
        loan.setId(id);
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
        loan.setDateApproved(reachedCredit);
        return loan;
    }

    private static User admin(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    @Nested
    @DisplayName("escalation")
    class Escalation {

        private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        @Test
        @DisplayName("escalates a wait past the escalation point once: stamped, audited, and emailed to each administrator")
        void escalatesOnce() {
            Loan overdue = awaiting(1L, now.minusHours(50));
            when(loanRepository.findIdsDueForCreditEscalation(any())).thenReturn(List.of(1L));
            when(loanRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(overdue));
            when(userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN)).thenReturn(List.of(
                    admin("ops@innbucks.co.zw"), admin("ops@innbucks.co.zw"), admin(" "), admin(null),
                    admin("risk@innbucks.co.zw")));

            assertThat(service.escalateOverdue()).isEqualTo(1);

            ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(loanRepository).findIdsDueForCreditEscalation(cutoff.capture());
            assertThat(cutoff.getValue()).isBetween(now.minusHours(48).minusMinutes(1), now.minusHours(47));
            assertThat(overdue.getCreditEscalatedAt()).isNotNull();
            verify(loanRepository).save(overdue);
            ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
            verify(auditService).record(audit.capture());
            AuditLog row = audit.getValue().build();
            assertThat(row.getEventType()).isEqualTo("CREDIT_DECISION_ESCALATED");
            assertThat(row.getEntityId()).isEqualTo("1");
            assertThat(row.getActorId()).isEqualTo("credit-escalation-job");
            assertThat(row.getDetail()).startsWith("waitingHours=50 ");
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(notificationService).sendEmail(eq("ops@innbucks.co.zw"),
                    eq("Credit decision overdue: loan " + overdue.getReference()), body.capture());
            verify(notificationService).sendEmail(eq("risk@innbucks.co.zw"), anyString(), anyString());
            verifyNoMoreInteractions(notificationService);
            assertThat(body.getValue()).contains("Loan " + overdue.getReference() + ": waiting 50 hours")
                    .contains("longer than 48 hours").contains("target 24 hours");
        }

        @Test
        @DisplayName("re-checks each loan under its lock: one decided, escalated or resubmitted since the list is left alone")
        void recheckedUnderTheLock() {
            Loan decided = awaiting(2L, now.minusHours(50));
            decided.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
            Loan escalated = awaiting(3L, now.minusHours(50));
            escalated.setCreditEscalatedAt(now.minusHours(1));
            Loan resubmitted = awaiting(4L, now.minusHours(50));
            resubmitted.setCreditResubmittedAt(now.minusHours(1));
            when(loanRepository.findIdsDueForCreditEscalation(any())).thenReturn(List.of(2L, 3L, 4L, 5L));
            when(loanRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(decided));
            when(loanRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(escalated));
            when(loanRepository.findByIdForUpdate(4L)).thenReturn(Optional.of(resubmitted));
            when(loanRepository.findByIdForUpdate(5L)).thenReturn(Optional.empty());

            assertThat(service.escalateOverdue()).isZero();

            verify(loanRepository, never()).save(any());
            verifyNoInteractions(auditService, notificationService, userRepository);
            assertThat(escalated.getCreditEscalatedAt()).isEqualTo(now.minusHours(1));
        }

        @Test
        @DisplayName("one loan that fails leaves the others escalated, in a single email")
        void oneFailureDoesNotStopTheRun() {
            Loan first = awaiting(6L, now.minusHours(60));
            Loan third = awaiting(8L, now.minusHours(49));
            when(loanRepository.findIdsDueForCreditEscalation(any())).thenReturn(List.of(6L, 7L, 8L));
            when(loanRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(first));
            when(loanRepository.findByIdForUpdate(7L)).thenThrow(new IllegalStateException("lock timeout"));
            when(loanRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(third));
            when(userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN))
                    .thenReturn(List.of(admin("ops@innbucks.co.zw")));

            assertThat(service.escalateOverdue()).isEqualTo(2);

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(notificationService).sendEmail(eq("ops@innbucks.co.zw"), eq("2 credit decisions overdue"),
                    body.capture());
            assertThat(body.getValue()).contains("Loan " + first.getReference() + ": waiting 60 hours")
                    .contains("Loan " + third.getReference() + ": waiting 49 hours");
        }

        @Test
        @DisplayName("with no administrator email the escalation still stands; with nothing due nobody is emailed")
        void noRecipients() {
            Loan overdue = awaiting(9L, now.minusHours(50));
            when(loanRepository.findIdsDueForCreditEscalation(any())).thenReturn(List.of(9L));
            when(loanRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(overdue));
            when(userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN)).thenReturn(List.of(admin("")));

            assertThat(service.escalateOverdue()).isEqualTo(1);
            assertThat(overdue.getCreditEscalatedAt()).isNotNull();
            verifyNoInteractions(notificationService);

            when(loanRepository.findIdsDueForCreditEscalation(any())).thenReturn(List.of());
            assertThat(service.escalateOverdue()).isZero();
            verify(userRepository, times(1)).findByGroupsContaining(UserGroup.SUPER_ADMIN);
        }

        @Test
        @DisplayName("an email that cannot be queued does not undo the escalation")
        void emailFailureIsSwallowed() {
            Loan overdue = awaiting(10L, now.minusHours(50));
            when(loanRepository.findIdsDueForCreditEscalation(any())).thenReturn(List.of(10L));
            when(loanRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(overdue));
            when(userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN))
                    .thenReturn(List.of(admin("ops@innbucks.co.zw")));
            doThrow(new IllegalStateException("queue full")).when(notificationService)
                    .sendEmail(anyString(), anyString(), anyString());

            assertThat(service.escalateOverdue()).isEqualTo(1);
            assertThat(overdue.getCreditEscalatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("adherence report")
    class Report {

        private CreditDecision decision(long id, long loanId, CreditAction action, LocalDateTime at) {
            return CreditDecision.builder().id(id).loanId(loanId).action(action).performedAt(at).build();
        }

        private void approvedAt(Object[]... rows) {
            List<Object[]> list = new ArrayList<>(List.of(rows));
            when(loanRepository.findDateApprovedByIdIn(anyCollection())).thenReturn(list);
        }

        private void queue(long awaiting, long overdue, long escalated) {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{awaiting, overdue, escalated});
            when(loanRepository.countAwaitingCredit(any())).thenReturn(rows);
        }

        @Test
        @DisplayName("times each decision from when its loan reached Credit, a resubmission restarting the clock")
        void timesEachDecision() {
            LocalDateTime ssb42 = LocalDateTime.of(2026, 9, 3, 6, 5, 12);
            LocalDateTime ssb43 = LocalDateTime.of(2026, 9, 10, 8, 0);
            LocalDateTime ssb44 = LocalDateTime.of(2026, 9, 12, 8, 0);
            LocalDateTime resubmitted42 = LocalDateTime.of(2026, 9, 3, 8, 3, 10);
            when(creditDecisionRepository.findByActionInAndPerformedAtBetweenOrderByIdAsc(anyCollection(), any(), any()))
                    .thenReturn(List.of(
                            decision(17, 42, CreditAction.RETURNED, LocalDateTime.of(2026, 9, 3, 7, 12, 45)),
                            decision(19, 42, CreditAction.APPROVED, LocalDateTime.of(2026, 9, 3, 9, 40, 2)),
                            decision(20, 43, CreditAction.REJECTED, ssb43.plusHours(30)),
                            decision(21, 44, CreditAction.APPROVED, ssb44.plusHours(10)),
                            decision(22, 45, CreditAction.REJECTED, LocalDateTime.of(2026, 9, 14, 8, 0))));
            approvedAt(new Object[]{42L, ssb42}, new Object[]{43L, ssb43}, new Object[]{44L, ssb44},
                    new Object[]{45L, null});
            when(creditDecisionRepository.findByLoanIdInAndActionOrderByPerformedAtAsc(anyCollection(),
                    eq(CreditAction.RESUBMITTED))).thenReturn(List.of(
                    decision(18, 42, CreditAction.RESUBMITTED, resubmitted42)));
            queue(3, 1, 0);

            CreditTurnaroundReportResponse report = service.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

            // 42 returned after 1.1h and approved 1.6h after the answer; 43 took 30h; 44 took 10h; 45 never reached Credit.
            assertThat(report.decisions()).isEqualTo(4);
            assertThat(report.withinTarget()).isEqualTo(3);
            assertThat(report.adherencePercent()).isEqualByComparingTo("75.0");
            assertThat(report.averageHours()).isEqualByComparingTo("10.7");
            assertThat(report.medianHours()).isEqualByComparingTo("5.8");
            assertThat(report.longestHours()).isEqualByComparingTo("30.0");
            assertThat(report.unmeasured()).isEqualTo(1);
            assertThat(report.byAction()).containsExactly(
                    new ActionTurnaround(CreditAction.APPROVED, 2, 2, new java.math.BigDecimal("5.8")),
                    new ActionTurnaround(CreditAction.REJECTED, 1, 0, new java.math.BigDecimal("30.0")),
                    new ActionTurnaround(CreditAction.RETURNED, 1, 1, new java.math.BigDecimal("1.1")));
            assertThat(report.targetHours()).isEqualTo(24);
            assertThat(report.escalationHours()).isEqualTo(48);
            assertThat(report.awaiting()).isEqualTo(3);
            assertThat(report.overdue()).isEqualTo(1);
            assertThat(report.escalated()).isZero();
        }

        @Test
        @DisplayName("the period is the market's days: Harare midnight to midnight, in UTC")
        void periodIsMarketDays() {
            when(creditDecisionRepository.findByActionInAndPerformedAtBetweenOrderByIdAsc(anyCollection(), any(), any()))
                    .thenReturn(List.of());
            queue(0, 0, 0);

            service.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<CreditAction>> actions = ArgumentCaptor.forClass(Collection.class);
            ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(creditDecisionRepository).findByActionInAndPerformedAtBetweenOrderByIdAsc(actions.capture(),
                    from.capture(), to.capture());
            assertThat(actions.getValue()).containsExactlyInAnyOrder(CreditAction.APPROVED, CreditAction.REJECTED,
                    CreditAction.RETURNED);
            assertThat(from.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 31, 22, 0));
            assertThat(to.getValue()).isBefore(LocalDateTime.of(2026, 9, 30, 22, 0))
                    .isAfter(LocalDateTime.of(2026, 9, 30, 21, 59));
            verify(loanRepository, never()).findDateApprovedByIdIn(any());
        }

        @Test
        @DisplayName("an empty period has no averages rather than zeros, and defaults to this month so far")
        void emptyPeriod() {
            when(creditDecisionRepository.findByActionInAndPerformedAtBetweenOrderByIdAsc(anyCollection(), any(), any()))
                    .thenReturn(List.of());
            queue(2, 0, 0);

            CreditTurnaroundReportResponse report = service.report(null, null);

            assertThat(report.fromDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(report.toDate()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(report.decisions()).isZero();
            assertThat(report.adherencePercent()).isNull();
            assertThat(report.averageHours()).isNull();
            assertThat(report.medianHours()).isNull();
            assertThat(report.longestHours()).isNull();
            assertThat(report.byAction()).isEmpty();
            assertThat(report.awaiting()).isEqualTo(2);
        }

        @Test
        @DisplayName("an inverted period is refused")
        void invertedPeriod() {
            assertThatThrownBy(() -> service.report(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("fromDate must not be after toDate");
        }

        @Test
        @DisplayName("a decision's wait began at the last resubmission before it, else at SSB's approval if earlier")
        void enteredBefore() {
            LocalDateTime approved = LocalDateTime.of(2026, 9, 3, 6, 0);
            CreditDecision decided = decision(1, 42, CreditAction.APPROVED, approved.plusHours(10));
            List<LocalDateTime> resubmissions = List.of(approved.plusHours(2), approved.plusHours(5),
                    approved.plusHours(12));

            assertThat(CreditTurnaroundService.enteredBefore(decided, approved, resubmissions))
                    .isEqualTo(approved.plusHours(5));
            assertThat(CreditTurnaroundService.enteredBefore(decided, approved, List.of())).isEqualTo(approved);
            assertThat(CreditTurnaroundService.enteredBefore(decided, approved.plusHours(11), List.of())).isNull();
            assertThat(CreditTurnaroundService.enteredBefore(decided, null, List.of())).isNull();
        }
    }
}
