package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who hears about a flagged loan, when, and in what words: Human Capital and Payroll for someone who left, Credit for
 * someone suspended or on unpaid leave, once the register change commits, one email each.
 */
class StaffLoanEmploymentFlagNotifierTest {

    private static final StaffEmploymentStatus ACTIVE = null;

    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final StaffLoanEmploymentFlagNotifier notifier = notifier(List.of(" payroll@innbucks.co.zw ",
            "Payroll@InnBucks.co.zw", ""));

    {
        when(users.findByGroupsContaining(UserGroup.HUMAN_CAPITAL)).thenReturn(List.of(user("hc1@innbucks.co.zw"),
                user(null), user(" ")));
        when(users.findByGroupsContaining(UserGroup.CREDIT_MANAGER)).thenReturn(List.of(user("credit1@innbucks.co.zw")));
    }

    @AfterEach
    void endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(notifier);
    }

    @Test
    @DisplayName("someone who left: Human Capital and Payroll (once, whatever the spelling), not Credit, in these words")
    void leftOwing() {
        notifier.notifyAfterCommit(nyasha(ACTIVE, StaffEmploymentStatus.RESIGNED));

        String body = """
                The employment status of a borrower with a Staff Grocery Loan already paid out has changed on the \
                staff register:

                SGL-2026-000151, employee E1001, now RESIGNED: USD 250.00 outstanding, due 20 November 2026. Recover \
                it from their terminal benefits.

                The loans are listed in the Staff Grocery Loans screen of the InnBucks Lending portal.""";
        String subject = "Staff Grocery Loan SGL-2026-000151: borrower now RESIGNED";
        verify(notifications).sendEmail("hc1@innbucks.co.zw", subject, body);
        verify(notifications).sendEmail("payroll@innbucks.co.zw", subject, body);
        verify(notifications, times(2)).sendEmail(anyString(), anyString(), anyString());
        verify(users, never()).findByGroupsContaining(UserGroup.CREDIT_MANAGER);
    }

    @Test
    @DisplayName("someone suspended or on unpaid leave: Credit alone decides the due date")
    void onLeave() {
        notifier.notifyAfterCommit(nyasha(ACTIVE, StaffEmploymentStatus.UNPAID_LEAVE));

        verify(notifications).sendEmail(eq("credit1@innbucks.co.zw"),
                eq("Staff Grocery Loan SGL-2026-000151: borrower now UNPAID_LEAVE"),
                eq(StaffLoanEmploymentFlagNotifier.body(List.of(nyasha(ACTIVE, StaffEmploymentStatus.UNPAID_LEAVE)))));
        verify(notifications, times(1)).sendEmail(anyString(), anyString(), anyString());
        assertThat(StaffLoanEmploymentFlagNotifier.line(nyasha(ACTIVE, StaffEmploymentStatus.UNPAID_LEAVE)))
                .isEqualTo("SGL-2026-000151, employee E1001, now UNPAID_LEAVE: USD 250.00 outstanding, due 20"
                        + " November 2026. Credit decides what becomes of its due date.");
    }

    @Test
    @DisplayName("a flag withdrawn reaches whoever was told about it, and says so")
    void withdrawn() {
        notifier.notifyAfterCommit(nyasha(StaffEmploymentStatus.RESIGNED, ACTIVE));

        verify(notifications).sendEmail(eq("hc1@innbucks.co.zw"),
                eq("Staff Grocery Loan SGL-2026-000151: borrower ACTIVE again"), anyString());
        verify(notifications).sendEmail(eq("payroll@innbucks.co.zw"), anyString(), anyString());
        verify(notifications, times(2)).sendEmail(anyString(), anyString(), anyString());
        assertThat(StaffLoanEmploymentFlagNotifier.line(nyasha(StaffEmploymentStatus.RESIGNED, ACTIVE)))
                .isEqualTo("SGL-2026-000151, employee E1001, ACTIVE again (was RESIGNED): USD 250.00 outstanding, due"
                        + " 20 November 2026. Do not recover it from terminal benefits after all. It is collected from"
                        + " salary as usual.");
    }

    @Test
    @DisplayName("a move between the two tells both sides")
    void movedFromLeaveToLeft() {
        notifier.notifyAfterCommit(nyasha(StaffEmploymentStatus.SUSPENDED, StaffEmploymentStatus.TERMINATED));

        verify(notifications).sendEmail(eq("credit1@innbucks.co.zw"), anyString(), anyString());
        verify(notifications).sendEmail(eq("hc1@innbucks.co.zw"), anyString(), anyString());
        verify(notifications).sendEmail(eq("payroll@innbucks.co.zw"), anyString(), anyString());
        assertThat(StaffLoanEmploymentFlagNotifier.line(nyasha(StaffEmploymentStatus.TERMINATED,
                StaffEmploymentStatus.SUSPENDED))).isEqualTo("SGL-2026-000151, employee E1001, now SUSPENDED (was"
                + " TERMINATED): USD 250.00 outstanding, due 20 November 2026. Do not recover it from terminal"
                + " benefits after all. Credit decides what becomes of its due date.");
    }

    @Test
    @DisplayName("inside a transaction nothing goes until it commits, then one email per recipient for all of it")
    void afterCommitOnly() {
        TransactionSynchronizationManager.initSynchronization();
        notifier.notifyAfterCommit(nyasha(ACTIVE, StaffEmploymentStatus.RESIGNED));
        notifier.notifyAfterCommit(new StaffLoanEmploymentFlagNotifier.Change("SGL-2026-000158", "E1012",
                new BigDecimal("300.00"), "USD", LocalDate.of(2026, 11, 20), ACTIVE, StaffEmploymentStatus.TERMINATED));
        verifyNoInteractions(notifications);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations,
                TransactionSynchronization.STATUS_COMMITTED);

        String body = StaffLoanEmploymentFlagNotifier.body(List.of(nyasha(ACTIVE, StaffEmploymentStatus.RESIGNED),
                new StaffLoanEmploymentFlagNotifier.Change("SGL-2026-000158", "E1012", new BigDecimal("300.00"), "USD",
                        LocalDate.of(2026, 11, 20), ACTIVE, StaffEmploymentStatus.TERMINATED)));
        assertThat(body).startsWith("The employment status of these borrowers").contains("SGL-2026-000151")
                .contains("SGL-2026-000158");
        verify(notifications).sendEmail("hc1@innbucks.co.zw", "Staff Grocery Loans: 2 borrowers' employment status"
                + " changed", body);
        verify(notifications).sendEmail(eq("payroll@innbucks.co.zw"), anyString(), eq(body));
        verify(users, times(1)).findByGroupsContaining(UserGroup.HUMAN_CAPITAL);
        assertThat(TransactionSynchronizationManager.getResource(notifier)).isNull();
    }

    @Test
    @DisplayName("a transaction that rolls back sends nothing")
    void rolledBack() {
        TransactionSynchronizationManager.initSynchronization();
        notifier.notifyAfterCommit(nyasha(ACTIVE, StaffEmploymentStatus.RESIGNED));

        TransactionSynchronizationUtils.invokeAfterCompletion(TransactionSynchronizationManager.getSynchronizations(),
                TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(notifications);
        assertThat(TransactionSynchronizationManager.getResource(notifier)).isNull();
    }

    @Test
    @DisplayName("nobody to tell, or an email that cannot be queued, never throws")
    void neverThrows() {
        when(users.findByGroupsContaining(any())).thenReturn(List.of());
        StaffLoanEmploymentFlagNotifier noPayroll = notifier(List.of());
        assertThatCode(() -> noPayroll.notifyAfterCommit(nyasha(ACTIVE, StaffEmploymentStatus.RESIGNED)))
                .doesNotThrowAnyException();
        verifyNoInteractions(notifications);

        when(users.findByGroupsContaining(UserGroup.HUMAN_CAPITAL)).thenReturn(List.of(user("hc1@innbucks.co.zw")));
        doThrow(new IllegalStateException("executor full")).when(notifications)
                .sendEmail(anyString(), anyString(), anyString());
        assertThatCode(() -> notifier.notifyAfterCommit(nyasha(ACTIVE, StaffEmploymentStatus.RESIGNED)))
                .doesNotThrowAnyException();
        verify(notifications, times(2)).sendEmail(anyString(), anyString(), anyString());
    }

    private StaffLoanEmploymentFlagNotifier notifier(List<String> payrollEmails) {
        StaffLoanProperties properties = new StaffLoanProperties();
        properties.setPayrollEmails(payrollEmails);
        return new StaffLoanEmploymentFlagNotifier(users, notifications, properties);
    }

    private static StaffLoanEmploymentFlagNotifier.Change nyasha(StaffEmploymentStatus from,
                                                                 StaffEmploymentStatus to) {
        return new StaffLoanEmploymentFlagNotifier.Change("SGL-2026-000151", "E1001", new BigDecimal("250.00"), "USD",
                LocalDate.of(2026, 11, 20), from, to);
    }

    private static User user(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }
}
