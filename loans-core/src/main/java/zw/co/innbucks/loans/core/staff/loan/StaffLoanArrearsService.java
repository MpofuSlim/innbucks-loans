package zw.co.innbucks.loans.core.staff.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static zw.co.innbucks.loans.core.voucher.VoucherSettlementService.cell;

/**
 * The daily arrears and exception report for Credit and Human Capital (FR-SGL-045): every paid-out Staff Grocery Loan
 * not recovered in full, with how far past its due date it is, whether it is escalated to Credit (BRD 3.8), written
 * off, or owed by a borrower who is no longer ACTIVE. Read in the portal, downloaded as CSV, and emailed every morning
 * to Credit and Human Capital where the Staff Grocery Loan jobs run ({@link StaffLoanArrearsReportJob}).
 *
 * <p>It reads loans' own records. Until the core banking collection reports recoveries, a loan still DISBURSED after
 * its due date is taken as not recovered, and what is owed is what loans knows.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffLoanArrearsService {

    static final String SENT = "STAFF_LOAN_ARREARS_REPORT_SENT";
    static final String SYSTEM_ACTOR = "staff-loan-arrears-report-job";
    /** The most loans one email lists; the rest are in the portal. */
    static final int EMAIL_LINE_LIMIT = 200;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final String[] CSV_HEADER = {"reference", "employeeNumber", "fullName", "department",
            "employmentStatus", "grade", "msisdn", "merchantCode", "status", "currency", "amount", "outstanding",
            "disbursedAt", "dueDate", "daysPastDue", "bucket", "inArrears", "escalated", "employmentFlag",
            "employmentFlagAction"};

    private final StaffLoanRepository loanRepository;
    private final StaffMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final StaffLoanPolicy policy;
    private final StaffLoanProperties properties;
    private final MarketTimeZone marketTimeZone;

    /** The report as loans' records stand now. */
    @Transactional(readOnly = true)
    public StaffLoanArrearsReport report() {
        LocalDate today = marketTimeZone.today();
        int grace = policy.arrearsGraceDays();
        int escalation = properties.getArrearsEscalationDays();
        List<StaffLoan> loans = loanRepository.findNotRecovered(today, StaffLoanStatus.DISBURSED,
                StaffLoanStatus.WRITTEN_OFF);
        Map<Long, StaffMember> members = loans.isEmpty() ? Map.of() : memberRepository.findAllById(
                        loans.stream().map(StaffLoan::getStaffMemberId).distinct().toList()).stream()
                .collect(Collectors.toMap(StaffMember::getId, Function.identity()));
        List<StaffLoanArrearsReport.Line> lines = loans.stream()
                .map(loan -> line(loan, members.get(loan.getStaffMemberId()), today, grace, escalation))
                .sorted(Comparator.comparingLong(StaffLoanArrearsReport.Line::daysPastDue).reversed()
                        .thenComparing(StaffLoanArrearsReport.Line::reference))
                .toList();
        return new StaffLoanArrearsReport(today, marketTimeZone.nowUtc(), grace, escalation, totals(lines), lines);
    }

    /** The report as a spreadsheet, and the day it describes, which names the file. */
    public record Csv(LocalDate asOf, String content) {

        public String fileName() {
            return "staff-loan-arrears-" + asOf + ".csv";
        }
    }

    /** The report's loans as CSV, one row each, for a spreadsheet. */
    @Transactional(readOnly = true)
    public Csv csv() {
        StaffLoanArrearsReport report = report();
        StringBuilder out = new StringBuilder(String.join(",", CSV_HEADER)).append("\r\n");
        for (StaffLoanArrearsReport.Line line : report.lines()) {
            StaffLoanEmploymentFlag flag = line.employmentFlag();
            out.append(String.join(",", cell(line.reference()), cell(line.employeeNumber()),
                            cell(line.fullName()), cell(line.department()),
                            line.employmentStatus() == null ? "" : line.employmentStatus().name(), cell(line.grade()),
                            cell(line.msisdn()), cell(line.merchantCode()), line.status().name(), line.currency(),
                            line.amount().toPlainString(), line.outstanding().toPlainString(),
                            line.disbursedAt() == null ? "" : marketTimeZone.render(line.disbursedAt()),
                            line.dueDate().toString(), String.valueOf(line.daysPastDue()), line.bucket().name(),
                            String.valueOf(line.inArrears()), String.valueOf(line.escalated()),
                            flag == null ? "" : flag.employmentStatus().name(),
                            flag == null ? "" : flag.action().name()))
                    .append("\r\n");
        }
        return new Csv(report.asOf(), out.toString());
    }

    /**
     * Emails the report to every CREDIT_MANAGER and HUMAN_CAPITAL user with an email address, one each, naming loan
     * references and employee numbers but never the borrowers. Sent even when nothing is in arrears, so the morning
     * email's absence always means something went wrong. Audited. Never throws.
     */
    public void sendDaily() {
        StaffLoanArrearsReport report;
        try {
            report = report();
        } catch (RuntimeException ex) {
            log.error("The daily Staff Grocery Loan arrears report could not be produced", ex);
            return;
        }
        Set<String> recipients = new LinkedHashSet<>();
        for (UserGroup group : List.of(UserGroup.CREDIT_MANAGER, UserGroup.HUMAN_CAPITAL)) {
            userRepository.findByGroupsContaining(group).stream().map(User::getEmail).map(StringUtils::trimToNull)
                    .filter(Objects::nonNull).map(email -> email.toLowerCase(Locale.ROOT)).forEach(recipients::add);
        }
        if (recipients.isEmpty()) {
            log.warn("The daily Staff Grocery Loan arrears report has {} loan(s), but no Credit or Human Capital user"
                    + " has an email address to send it to", report.lines().size());
            return;
        }
        String subject = subject(report);
        String body = body(report);
        int sent = 0;
        for (String recipient : recipients) {
            try {
                notificationService.sendEmail(recipient, subject, body);
                sent++;
            } catch (RuntimeException ex) {
                log.error("The daily Staff Grocery Loan arrears report could not be queued for one recipient", ex);
            }
        }
        log.info("Daily Staff Grocery Loan arrears report for {}: {} loan(s), {} escalated, queued for {} of {}"
                        + " recipient(s)", report.asOf(), report.lines().size(), escalated(report).size(), sent,
                recipients.size());
        try {
            auditService.record(AuditLog.builder()
                    .eventType(SENT)
                    .entityType("STAFF_LOAN_ARREARS_REPORT").entityId(report.asOf().toString())
                    .actorId(SYSTEM_ACTOR).channelUsed("system")
                    .detail("loans:" + report.lines().size() + ";escalated:" + escalated(report).size()
                            + ";recipients:" + sent));
        } catch (RuntimeException ex) {
            log.error("The daily Staff Grocery Loan arrears report was sent but could not be audited", ex);
        }
    }

    private StaffLoanArrearsReport.Line line(StaffLoan loan, StaffMember member, LocalDate today, int grace,
                                             int escalation) {
        long daysPastDue = Math.max(0, ChronoUnit.DAYS.between(loan.getDueDate(), today));
        boolean escalated = loan.getStatus() == StaffLoanStatus.DISBURSED && daysPastDue >= escalation;
        return new StaffLoanArrearsReport.Line(loan.getId(), loan.getReference(), loan.getEmployeeNumber(),
                loan.getFullName(), member == null ? null : member.getDepartment(),
                member == null ? null : member.getEmploymentStatus(), loan.getGrade(),
                MsisdnUtils.mask(loan.getMsisdn()), loan.getMerchant().getMerchantCode(), loan.getStatus(),
                loan.getCurrency(), loan.getAmount(), loan.outstanding(), loan.getDisbursedAt(), loan.getDueDate(),
                daysPastDue, StaffLoanArrearsBucket.of(daysPastDue), loan.inArrearsOn(today, grace), escalated,
                StaffLoanEmploymentFlag.of(loan));
    }

    private static List<StaffLoanArrearsReport.Totals> totals(List<StaffLoanArrearsReport.Line> lines) {
        Map<String, List<StaffLoanArrearsReport.Line>> byCurrency = lines.stream()
                .collect(Collectors.groupingBy(StaffLoanArrearsReport.Line::currency, TreeMap::new,
                        Collectors.toList()));
        List<StaffLoanArrearsReport.Totals> totals = new ArrayList<>();
        byCurrency.forEach((currency, group) -> {
            List<StaffLoanArrearsReport.Line> writtenOff = group.stream()
                    .filter(line -> line.status() == StaffLoanStatus.WRITTEN_OFF).toList();
            List<StaffLoanArrearsReport.BucketTotals> buckets = new ArrayList<>();
            for (StaffLoanArrearsBucket bucket : StaffLoanArrearsBucket.values()) {
                List<StaffLoanArrearsReport.Line> in = group.stream().filter(line -> line.bucket() == bucket).toList();
                buckets.add(new StaffLoanArrearsReport.BucketTotals(bucket, in.size(), sum(in)));
            }
            totals.add(new StaffLoanArrearsReport.Totals(currency, group.size(), sum(group),
                    count(group, line -> line.daysPastDue() > 0), count(group, StaffLoanArrearsReport.Line::inArrears),
                    count(group, StaffLoanArrearsReport.Line::escalated), writtenOff.size(), sum(writtenOff),
                    count(group, line -> line.employmentFlag() != null), buckets));
        });
        return totals;
    }

    private static int count(List<StaffLoanArrearsReport.Line> lines, Predicate<StaffLoanArrearsReport.Line> test) {
        return (int) lines.stream().filter(test).count();
    }

    private static BigDecimal sum(List<StaffLoanArrearsReport.Line> lines) {
        return lines.stream().map(StaffLoanArrearsReport.Line::outstanding)
                .reduce(BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY), BigDecimal::add);
    }

    private static List<StaffLoanArrearsReport.Line> escalated(StaffLoanArrearsReport report) {
        return report.lines().stream().filter(StaffLoanArrearsReport.Line::escalated).toList();
    }

    static String subject(StaffLoanArrearsReport report) {
        if (report.lines().isEmpty()) {
            return "Staff Grocery Loans, " + DAY.format(report.asOf()) + ": none in arrears";
        }
        return "Staff Grocery Loans not recovered in full, " + DAY.format(report.asOf()) + ": "
                + report.lines().size() + (report.lines().size() == 1 ? " loan, " : " loans, ")
                + report.totals().stream().map(t -> t.currency() + " " + t.outstanding().toPlainString())
                .collect(Collectors.joining(" and "));
    }

    static String body(StaffLoanArrearsReport report) {
        if (report.lines().isEmpty()) {
            return "No paid-out Staff Grocery Loan is past its due date, written off, or owed by a borrower who is no"
                    + " longer active, as at " + DAY.format(report.asOf()) + ".";
        }
        StringBuilder body = new StringBuilder("Staff Grocery Loans not recovered in full as at ")
                .append(DAY.format(report.asOf())).append(":\n\n");
        for (StaffLoanArrearsReport.Totals totals : report.totals()) {
            body.append(totals.currency()).append(": ").append(totals.loans())
                    .append(totals.loans() == 1 ? " loan, " : " loans, ").append(totals.outstanding().toPlainString())
                    .append(" outstanding. ").append(totals.overdue()).append(" past the due date, ")
                    .append(totals.escalated()).append(" escalated to Credit, ").append(totals.writtenOff())
                    .append(" written off, ").append(totals.employmentFlagged())
                    .append(totals.employmentFlagged() == 1 ? " borrower" : " borrowers")
                    .append(" no longer active.\n");
        }
        List<StaffLoanArrearsReport.Line> escalated = escalated(report);
        if (!escalated.isEmpty()) {
            body.append("\nEscalated to Credit, unpaid ").append(report.escalationDays())
                    .append(" days or more after the due date:\n");
            escalated.stream().limit(EMAIL_LINE_LIMIT).forEach(line -> body.append(line(line)).append('\n'));
        }
        body.append("\nAll loans, most overdue first:\n");
        report.lines().stream().limit(EMAIL_LINE_LIMIT).forEach(line -> body.append(line(line)).append('\n'));
        if (report.lines().size() > EMAIL_LINE_LIMIT) {
            body.append("... and ").append(report.lines().size() - EMAIL_LINE_LIMIT).append(" more.\n");
        }
        return body.append("\nThe full report, with names and departments, is the Staff Grocery Loan arrears report"
                + " in the InnBucks Lending portal, which can also be downloaded as a spreadsheet.").toString();
    }

    /** One loan for the email: its reference and employee number, never the borrower's name. */
    static String line(StaffLoanArrearsReport.Line line) {
        String when;
        if (line.status() == StaffLoanStatus.WRITTEN_OFF) {
            when = "written off";
        } else if (line.daysPastDue() > 0) {
            when = line.daysPastDue() + (line.daysPastDue() == 1 ? " day" : " days") + " past the due date ("
                    + DAY.format(line.dueDate()) + ")";
        } else {
            when = "due " + DAY.format(line.dueDate());
        }
        String text = line.reference() + ", employee " + line.employeeNumber() + ", " + when + ", "
                + line.currency() + " " + line.outstanding().toPlainString() + " outstanding";
        StaffLoanEmploymentFlag flag = line.employmentFlag();
        if (flag != null) {
            text += "; " + flag.employmentStatus() + ": " + switch (flag.action()) {
                case RECOVER_FROM_TERMINAL_BENEFITS -> "recover from terminal benefits";
                case CREDIT_TO_DECIDE -> "Credit decides its due date";
            };
        }
        return text + ".";
    }
}
