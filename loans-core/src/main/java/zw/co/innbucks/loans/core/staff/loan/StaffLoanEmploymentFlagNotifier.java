package zw.co.innbucks.loans.core.staff.loan;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tells the people a flagged loan concerns (FR-SGL-007, BRD 3.8), once the register change that flagged it commits:
 * Human Capital and Payroll when a borrower left owing (recovered from their terminal benefits), Credit when one is
 * suspended or on unpaid leave (what becomes of the due date is Credit's decision). A change tells everyone either the
 * old or the new flag concerns, so whoever was told about a flag hears when it no longer applies. One email per
 * recipient per register approval, naming loan references and employee numbers, never the borrowers.
 */
@Slf4j
@Component
public class StaffLoanEmploymentFlagNotifier {

    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);

    /**
     * One paid-out loan's flag moving from {@code from} to {@code to}, each the borrower's status, null for ACTIVE.
     */
    public record Change(String reference, String employeeNumber, BigDecimal outstanding, String currency,
                         LocalDate dueDate, StaffEmploymentStatus from, StaffEmploymentStatus to) {

        boolean concerns(EmploymentFlagAction action) {
            return action == EmploymentFlagAction.of(from) || action == EmploymentFlagAction.of(to);
        }
    }

    /** What one transaction has to send, with the group addresses it has already read. */
    private static final class Pending {
        private final List<Change> changes = new ArrayList<>();
        private final Map<UserGroup, List<String>> groupEmails = new EnumMap<>(UserGroup.class);
    }

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final List<String> payrollEmails;

    public StaffLoanEmploymentFlagNotifier(UserRepository userRepository, NotificationService notificationService,
                                           StaffLoanProperties properties) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.payrollEmails = properties.getPayrollEmails().stream().map(StringUtils::trimToNull)
                .filter(Objects::nonNull).distinct().toList();
        if (payrollEmails.isEmpty()) {
            log.warn("[startup] No Payroll mailbox (loans.staff-loans.payroll-emails): when a borrower leaves owing a"
                    + " Staff Grocery Loan, Human Capital alone is told to recover it from their terminal benefits");
        } else {
            log.info("[startup] When a borrower leaves owing a Staff Grocery Loan, Payroll is told at {} mailbox(es)"
                    + " as well as Human Capital", payrollEmails.size());
        }
    }

    /**
     * Sends {@code change} once the current transaction commits, with any others it makes; at once when there is none.
     * The group addresses are read now, inside it.
     */
    public void notifyAfterCommit(Change change) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            Pending now = new Pending();
            add(now, change);
            send(now);
            return;
        }
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(this);
        if (pending == null) {
            Pending created = new Pending();
            TransactionSynchronizationManager.bindResource(this, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(created);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(StaffLoanEmploymentFlagNotifier.this);
                }
            });
            pending = created;
        }
        add(pending, change);
    }

    private void add(Pending pending, Change change) {
        pending.changes.add(change);
        if (change.concerns(EmploymentFlagAction.RECOVER_FROM_TERMINAL_BENEFITS)) {
            pending.groupEmails.computeIfAbsent(UserGroup.HUMAN_CAPITAL, this::emailsOf);
        }
        if (change.concerns(EmploymentFlagAction.CREDIT_TO_DECIDE)) {
            pending.groupEmails.computeIfAbsent(UserGroup.CREDIT_MANAGER, this::emailsOf);
        }
    }

    private List<String> emailsOf(UserGroup group) {
        return userRepository.findByGroupsContaining(group).stream().map(User::getEmail)
                .map(StringUtils::trimToNull).filter(Objects::nonNull).toList();
    }

    /** Never throws: the register change has committed, and an email it could not send does not undo it. */
    private void send(Pending pending) {
        Map<String, List<Change>> byRecipient = new LinkedHashMap<>();
        for (Change change : pending.changes) {
            if (change.concerns(EmploymentFlagAction.RECOVER_FROM_TERMINAL_BENEFITS)) {
                pending.groupEmails.getOrDefault(UserGroup.HUMAN_CAPITAL, List.of())
                        .forEach(email -> add(byRecipient, email, change));
                payrollEmails.forEach(email -> add(byRecipient, email, change));
            }
            if (change.concerns(EmploymentFlagAction.CREDIT_TO_DECIDE)) {
                pending.groupEmails.getOrDefault(UserGroup.CREDIT_MANAGER, List.of())
                        .forEach(email -> add(byRecipient, email, change));
            }
        }
        if (byRecipient.isEmpty()) {
            log.warn("{} Staff Grocery Loan employment flag change(s), but nobody to tell has an email address:"
                            + " {}", pending.changes.size(),
                    pending.changes.stream().map(Change::reference).collect(Collectors.joining(", ")));
            return;
        }
        byRecipient.forEach((recipient, changes) -> {
            try {
                notificationService.sendEmail(recipient, subject(changes), body(changes));
            } catch (RuntimeException ex) {
                log.error("A Staff Grocery Loan employment flag email could not be queued", ex);
            }
        });
        log.info("Staff Grocery Loan employment flag change(s) {} emailed to {} recipient(s)",
                pending.changes.stream().map(Change::reference).collect(Collectors.joining(", ")),
                byRecipient.size());
    }

    private static void add(Map<String, List<Change>> byRecipient, String email, Change change) {
        List<Change> changes = byRecipient.computeIfAbsent(email.toLowerCase(Locale.ROOT), key -> new ArrayList<>());
        if (!changes.contains(change)) {
            changes.add(change);
        }
    }

    static String subject(List<Change> changes) {
        if (changes.size() > 1) {
            return "Staff Grocery Loans: " + changes.size() + " borrowers' employment status changed";
        }
        Change change = changes.getFirst();
        return "Staff Grocery Loan " + change.reference() + ": borrower " + (change.to() == null ? "ACTIVE again"
                : "now " + change.to());
    }

    static String body(List<Change> changes) {
        return "The employment status of " + (changes.size() == 1 ? "a borrower" : "these borrowers")
                + " with a Staff Grocery Loan already paid out has changed on the staff register:\n\n"
                + changes.stream().map(StaffLoanEmploymentFlagNotifier::line).collect(Collectors.joining("\n"))
                + "\n\nThe loans are listed in the Staff Grocery Loans screen of the InnBucks Lending portal.";
    }

    /** One loan: whose, its new status, what is owed, and what is to be done. */
    static String line(Change change) {
        String status = change.to() == null ? "ACTIVE again" : "now " + change.to();
        if (change.from() != null) {
            status += " (was " + change.from() + ")";
        }
        String line = change.reference() + ", employee " + change.employeeNumber() + ", " + status + ": "
                + change.currency() + " " + change.outstanding() + " outstanding, due " + DUE.format(change.dueDate())
                + ". ";
        EmploymentFlagAction before = EmploymentFlagAction.of(change.from());
        EmploymentFlagAction after = EmploymentFlagAction.of(change.to());
        if (before == EmploymentFlagAction.RECOVER_FROM_TERMINAL_BENEFITS
                && after != EmploymentFlagAction.RECOVER_FROM_TERMINAL_BENEFITS) {
            line += "Do not recover it from terminal benefits after all. ";
        }
        if (after == null) {
            return line + "It is collected from salary as usual.";
        }
        return line + switch (after) {
            case RECOVER_FROM_TERMINAL_BENEFITS -> "Recover it from their terminal benefits.";
            case CREDIT_TO_DECIDE -> "Credit decides what becomes of its due date.";
        };
    }
}
