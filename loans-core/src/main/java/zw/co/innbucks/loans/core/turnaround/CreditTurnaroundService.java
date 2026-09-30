package zw.co.innbucks.loans.core.turnaround;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecision;
import zw.co.innbucks.loans.core.loan.CreditDecisionRepository;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundReportResponse.ActionTurnaround;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Credit's turnaround against its service level (FR-SSB-015 / FR-PBL-030): an application waits for a credit
 * decision from when it reaches Credit, which is SSB's approval or the originator's last answer to a return. Past
 * the target it is overdue; past the escalation point it is escalated, once per wait: audited, logged at ERROR and
 * emailed to the platform's administrators. The adherence report times every decision made in a period.
 */
@Slf4j
@Service
public class CreditTurnaroundService {

    static final String ESCALATED = "CREDIT_DECISION_ESCALATED";
    static final String SYSTEM_ACTOR = "credit-escalation-job";
    /** The decisions a wait ends in; a resubmission starts one. */
    private static final EnumSet<CreditAction> DECISIONS =
            EnumSet.of(CreditAction.APPROVED, CreditAction.REJECTED, CreditAction.RETURNED);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final ServiceLevelService serviceLevelService;
    private final LoanRepository loanRepository;
    private final CreditDecisionRepository creditDecisionRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;
    private final TransactionTemplate transactionTemplate;

    public CreditTurnaroundService(ServiceLevelService serviceLevelService, LoanRepository loanRepository,
                                   CreditDecisionRepository creditDecisionRepository, UserRepository userRepository,
                                   NotificationService notificationService, AuditService auditService,
                                   MarketTimeZone marketTimeZone, PlatformTransactionManager transactionManager) {
        this.serviceLevelService = serviceLevelService;
        this.loanRepository = loanRepository;
        this.creditDecisionRepository = creditDecisionRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.marketTimeZone = marketTimeZone;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Escalated(String reference, long waitingHours) {
    }

    /**
     * Escalates every loan whose current wait for a credit decision has passed the escalation point, once each:
     * each in its own transaction, so one that fails leaves the others escalated. The administrators are emailed
     * one list per run.
     *
     * @return how many were escalated
     */
    public int escalateOverdue() {
        ServiceLevel level = serviceLevelService.serviceLevel(ServiceLevelStage.CREDIT_DECISION);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusHours(level.getEscalationHours());
        List<Escalated> escalated = new ArrayList<>();
        for (Long loanId : loanRepository.findIdsDueForCreditEscalation(cutoff)) {
            try {
                Escalated one = transactionTemplate.execute(tx -> escalate(loanId, cutoff, now, level));
                if (one != null) {
                    escalated.add(one);
                }
            } catch (RuntimeException ex) {
                log.error("Could not escalate loan {}'s overdue credit decision; the next run tries again", loanId, ex);
            }
        }
        if (!escalated.isEmpty()) {
            emailAdministrators(escalated, level);
        }
        return escalated.size();
    }

    private Escalated escalate(Long loanId, LocalDateTime cutoff, LocalDateTime now, ServiceLevel level) {
        Loan loan = loanRepository.findByIdForUpdate(loanId).orElse(null);
        // Re-checked under the lock: Credit may have decided it, or another run escalated it, since the list was read.
        if (loan == null || !CreditTurnaround.awaitingDecision(loan) || loan.getCreditEscalatedAt() != null
                || loan.creditQueueEnteredAt() == null || loan.creditQueueEnteredAt().isAfter(cutoff)) {
            return null;
        }
        loan.setCreditEscalatedAt(now);
        loanRepository.save(loan);
        long waited = CreditTurnaround.hoursBetween(loan.creditQueueEnteredAt(), now).longValue();
        log.error("CREDIT DECISION ESCALATED: loan {} has waited {} hours for a credit decision since {}, past the"
                        + " {}-hour escalation point (target {} hours); administrators notified (audited)",
                loan.getReference(), waited, loan.creditQueueEnteredAt(), level.getEscalationHours(),
                level.getTargetHours());
        audit(loan, "waitingHours=" + waited + " since=" + loan.creditQueueEnteredAt() + " targetHours="
                + level.getTargetHours() + " escalationHours=" + level.getEscalationHours());
        return new Escalated(loan.getReference(), waited);
    }

    /** One email per administrator per run; the references only, never the applicants. */
    private void emailAdministrators(List<Escalated> escalated, ServiceLevel level) {
        List<String> recipients = userRepository.findByGroupsContaining(UserGroup.SUPER_ADMIN).stream()
                .map(User::getEmail)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (recipients.isEmpty()) {
            log.warn("{} overdue credit decision(s) escalated, but no administrator has an email address",
                    escalated.size());
            return;
        }
        String subject = escalated.size() == 1
                ? "Credit decision overdue: loan " + escalated.getFirst().reference()
                : escalated.size() + " credit decisions overdue";
        String body = "These applications have waited longer than " + level.getEscalationHours()
                + " hours for a credit decision (target " + level.getTargetHours() + " hours):\n\n"
                + escalated.stream()
                .map(one -> "Loan " + one.reference() + ": waiting " + one.waitingHours() + " hours")
                .collect(Collectors.joining("\n"))
                + "\n\nThey are in the credit queue.";
        for (String recipient : recipients) {
            try {
                notificationService.sendEmail(recipient, subject, body);
            } catch (RuntimeException ex) {
                log.error("Escalation email could not be queued", ex);
            }
        }
    }

    /**
     * Times every credit decision made in the period (the market's days, both inclusive; this month so far by
     * default), from when its loan reached Credit, against the current service level; and counts the queue now.
     *
     * @throws ValidationException fromDate is after toDate
     */
    @Transactional(readOnly = true)
    public CreditTurnaroundReportResponse report(LocalDate fromDate, LocalDate toDate) {
        LocalDate today = marketTimeZone.today();
        LocalDate from = fromDate == null ? today.withDayOfMonth(1) : fromDate;
        LocalDate to = toDate == null ? today : toDate;
        if (from.isAfter(to)) {
            throw new ValidationException("fromDate must not be after toDate");
        }
        ServiceLevel level = serviceLevelService.serviceLevel(ServiceLevelStage.CREDIT_DECISION);
        List<CreditDecision> decisions = creditDecisionRepository.findByActionInAndPerformedAtBetweenOrderByIdAsc(
                DECISIONS, marketTimeZone.startOfDayUtc(from), marketTimeZone.endOfDayUtc(to));
        List<Long> loanIds = decisions.stream().map(CreditDecision::getLoanId).distinct().toList();
        Map<Long, LocalDateTime> approvedAt = new HashMap<>();
        Map<Long, List<LocalDateTime>> resubmittedAt = new HashMap<>();
        if (!loanIds.isEmpty()) {
            for (Object[] row : loanRepository.findDateApprovedByIdIn(loanIds)) {
                if (row[1] != null) {
                    approvedAt.put((Long) row[0], (LocalDateTime) row[1]);
                }
            }
            for (CreditDecision resubmission : creditDecisionRepository
                    .findByLoanIdInAndActionOrderByPerformedAtAsc(loanIds, CreditAction.RESUBMITTED)) {
                resubmittedAt.computeIfAbsent(resubmission.getLoanId(), id -> new ArrayList<>())
                        .add(resubmission.getPerformedAt());
            }
        }

        BigDecimal target = BigDecimal.valueOf(level.getTargetHours());
        List<BigDecimal> hours = new ArrayList<>();
        Map<CreditAction, List<BigDecimal>> hoursByAction = new HashMap<>();
        long unmeasured = 0;
        for (CreditDecision decision : decisions) {
            LocalDateTime entered = enteredBefore(decision, approvedAt.get(decision.getLoanId()),
                    resubmittedAt.getOrDefault(decision.getLoanId(), List.of()));
            if (entered == null) {
                unmeasured++;
                continue;
            }
            BigDecimal took = CreditTurnaround.hoursBetween(entered, decision.getPerformedAt());
            hours.add(took);
            hoursByAction.computeIfAbsent(decision.getAction(), action -> new ArrayList<>()).add(took);
        }

        List<ActionTurnaround> byAction = DECISIONS.stream()
                .filter(hoursByAction::containsKey)
                .map(action -> {
                    List<BigDecimal> taken = hoursByAction.get(action);
                    return new ActionTurnaround(action, taken.size(), withinTarget(taken, target), average(taken));
                })
                .toList();
        long within = withinTarget(hours, target);
        Object[] queue = loanRepository.countAwaitingCredit(
                LocalDateTime.now(ZoneOffset.UTC).minusHours(level.getTargetHours())).getFirst();
        return new CreditTurnaroundReportResponse(from, to, level.getTargetHours(), level.getEscalationHours(),
                hours.size(), within,
                hours.isEmpty() ? null : BigDecimal.valueOf(within).multiply(ONE_HUNDRED)
                        .divide(BigDecimal.valueOf(hours.size()), 1, RoundingMode.HALF_UP),
                average(hours), median(hours), hours.stream().max(Comparator.naturalOrder()).orElse(null),
                unmeasured, byAction, asLong(queue[0]), asLong(queue[1]), asLong(queue[2]));
    }

    /**
     * When the loan reached Credit for the wait this decision ended: its last resubmission before the decision, else
     * SSB's approval if that came first. Null when neither did: the loan was decided before SSB answered.
     */
    static LocalDateTime enteredBefore(CreditDecision decision, LocalDateTime approvedAt,
                                       List<LocalDateTime> resubmissions) {
        LocalDateTime decidedAt = decision.getPerformedAt();
        LocalDateTime entered = resubmissions.stream()
                .filter(at -> !at.isAfter(decidedAt))
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (entered == null && approvedAt != null && !approvedAt.isAfter(decidedAt)) {
            entered = approvedAt;
        }
        return entered;
    }

    private static long withinTarget(List<BigDecimal> hours, BigDecimal target) {
        return hours.stream().filter(took -> took.compareTo(target) <= 0).count();
    }

    private static BigDecimal average(List<BigDecimal> hours) {
        if (hours.isEmpty()) {
            return null;
        }
        return hours.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(hours.size()), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal median(List<BigDecimal> hours) {
        if (hours.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = hours.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
    }

    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private void audit(Loan loan, String detail) {
        try {
            auditService.record(AuditLog.builder()
                    .eventType(ESCALATED)
                    .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                    .actorId(SYSTEM_ACTOR).channelUsed(SYSTEM_ACTOR)
                    .detail(detail)
                    .correlationId(loan.getReference()));
        } catch (RuntimeException ex) {
            log.error("Audit of loan {}'s escalation failed", loan.getId(), ex);
        }
    }
}
