package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The daily arrears and exception report (FR-SGL-045): which loans it lists, how far past due each is and in which
 * bucket, which are escalated to Credit (BRD 3.8), the totals, the spreadsheet, and the morning email to Credit and
 * Human Capital. The clock stands at Wednesday 2 December 2026, 07:00:01 in Harare; escalation after 30 days.
 */
class StaffLoanArrearsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 12, 2);

    private final List<StaffLoan> loans = new ArrayList<>();
    private final List<StaffMember> members = new ArrayList<>();
    private final List<User> credit = new ArrayList<>();
    private final List<User> humanCapital = new ArrayList<>();
    private final StaffLoanProperties properties = new StaffLoanProperties();
    private StaffLoanRepository loanRepository;
    private NotificationService notifications;
    private AuditService auditService;
    private StaffLoanArrearsService service;

    @BeforeEach
    void setUp() {
        loanRepository = mock(StaffLoanRepository.class);
        when(loanRepository.findNotRecovered(eq(TODAY), eq(StaffLoanStatus.DISBURSED),
                eq(StaffLoanStatus.WRITTEN_OFF))).thenAnswer(i -> List.copyOf(loans));
        StaffMemberRepository memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findAllById(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return members.stream().filter(member -> ids.contains(member.getId())).toList();
        });
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByGroupsContaining(UserGroup.CREDIT_MANAGER)).thenAnswer(i -> credit);
        when(userRepository.findByGroupsContaining(UserGroup.HUMAN_CAPITAL)).thenAnswer(i -> humanCapital);
        notifications = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        properties.setArrearsEscalationDays(30);
        service = new StaffLoanArrearsService(loanRepository, memberRepository, userRepository, notifications,
                auditService, new StaffLoanPolicy(properties, new VoucherProperties()), properties,
                new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-12-02T05:00:01Z"), ZoneOffset.UTC)));
    }

    private StaffLoan loan(long id, String employeeNumber, String name, StaffLoanStatus status, String amount,
                           LocalDate due, StaffEmploymentStatus flag) {
        Merchant getMore = Merchant.builder().merchantCode("getmore-groceries").companyName("GetMore Groceries")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).build();
        StaffLoan loan = StaffLoan.builder().id(id).reference(String.format("SGL-2026-%06d", id)).staffMemberId(id)
                .employeeNumber(employeeNumber).fullName(name).msisdn("263773451290").grade("C4").merchant(getMore)
                .status(status).currency("USD").amount(new BigDecimal(amount)).totalRepayable(new BigDecimal(amount))
                .dueDate(due).disbursedAt(LocalDateTime.of(2026, 9, 18, 8, 12, 40)).employmentFlag(flag)
                .employmentFlaggedAt(flag == null ? null : LocalDateTime.of(2026, 10, 28, 8, 14, 3))
                .employmentFlagBatchId(flag == null ? null : 21L).build();
        loans.add(loan);
        members.add(StaffMember.builder().id(id).employeeNumber(employeeNumber).department("Retail Banking")
                .employmentStatus(flag == null ? StaffEmploymentStatus.ACTIVE : flag).build());
        return loan;
    }

    private static User user(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    /** One loan of each kind the report lists. */
    private void story() {
        loan(88, "E1088", "Tafadzwa Gumbo", StaffLoanStatus.WRITTEN_OFF, "200.00", LocalDate.of(2026, 7, 20), null);
        loan(127, "E1023", "Blessing Mutasa", StaffLoanStatus.DISBURSED, "150.00", LocalDate.of(2026, 10, 20), null);
        loan(151, "E1001", "Nyasha Dube", StaffLoanStatus.DISBURSED, "250.00", LocalDate.of(2026, 11, 20),
                StaffEmploymentStatus.RESIGNED);
        loan(160, "E1100", "Ruvimbo Chari", StaffLoanStatus.DISBURSED, "100.00", LocalDate.of(2026, 12, 20),
                StaffEmploymentStatus.SUSPENDED);
        loan(130, "E1030", "Simba Ncube", StaffLoanStatus.DISBURSED, "120.00", LocalDate.of(2026, 11, 2), null);
    }

    @Test
    @DisplayName("each loan with how far past due it is, its bucket, arrears and escalation; most overdue first")
    void lines() {
        story();

        StaffLoanArrearsReport report = service.report();

        assertThat(report.asOf()).isEqualTo(TODAY);
        assertThat(report.escalationDays()).isEqualTo(30);
        assertThat(report.lines()).extracting(StaffLoanArrearsReport.Line::reference,
                        StaffLoanArrearsReport.Line::daysPastDue, StaffLoanArrearsReport.Line::bucket,
                        StaffLoanArrearsReport.Line::inArrears, StaffLoanArrearsReport.Line::escalated)
                .containsExactly(
                        tuple("SGL-2026-000088", 135L, StaffLoanArrearsBucket.OVER_90_DAYS, true, false),
                        tuple("SGL-2026-000127", 43L, StaffLoanArrearsBucket.DAYS_31_TO_60, true, true),
                        tuple("SGL-2026-000130", 30L, StaffLoanArrearsBucket.DAYS_1_TO_30, true, true),
                        tuple("SGL-2026-000151", 12L, StaffLoanArrearsBucket.DAYS_1_TO_30, true, false),
                        tuple("SGL-2026-000160", 0L, StaffLoanArrearsBucket.NOT_DUE, false, false));
        assertThat(report.lines().get(3)).satisfies(line -> {
            assertThat(line.fullName()).isEqualTo("Nyasha Dube");
            assertThat(line.department()).isEqualTo("Retail Banking");
            assertThat(line.employmentStatus()).isEqualTo(StaffEmploymentStatus.RESIGNED);
            assertThat(line.msisdn()).doesNotContain("773451").endsWith("1290");
            assertThat(line.merchantCode()).isEqualTo("getmore-groceries");
            assertThat(line.employmentFlag().action()).isEqualTo(EmploymentFlagAction.RECOVER_FROM_TERMINAL_BENEFITS);
        });
        assertThat(report.lines().get(4).employmentFlag().action()).isEqualTo(EmploymentFlagAction.CREDIT_TO_DECIDE);
    }

    @Test
    @DisplayName("the grace keeps a loan just past due out of arrears; escalation follows its own setting")
    void graceAndEscalation() {
        properties.setArrearsGraceDays(14);
        properties.setArrearsEscalationDays(45);
        story();

        StaffLoanArrearsReport report = service.report();

        assertThat(report.graceDays()).isEqualTo(14);
        assertThat(report.lines()).extracting(StaffLoanArrearsReport.Line::reference,
                        StaffLoanArrearsReport.Line::inArrears, StaffLoanArrearsReport.Line::escalated)
                .containsExactly(
                        tuple("SGL-2026-000088", true, false),
                        tuple("SGL-2026-000127", true, false),
                        tuple("SGL-2026-000130", true, false),
                        tuple("SGL-2026-000151", false, false),
                        tuple("SGL-2026-000160", false, false));
    }

    @Test
    @DisplayName("totals count the lines per currency and bucket")
    void totals() {
        story();

        assertThat(service.report().totals()).singleElement().satisfies(totals -> {
            assertThat(totals.currency()).isEqualTo("USD");
            assertThat(totals.loans()).isEqualTo(5);
            assertThat(totals.outstanding()).isEqualByComparingTo("820.00");
            assertThat(totals.overdue()).isEqualTo(4);
            assertThat(totals.inArrears()).isEqualTo(4);
            assertThat(totals.escalated()).isEqualTo(2);
            assertThat(totals.writtenOff()).isEqualTo(1);
            assertThat(totals.writtenOffOutstanding()).isEqualByComparingTo("200.00");
            assertThat(totals.employmentFlagged()).isEqualTo(2);
            assertThat(totals.buckets()).extracting(StaffLoanArrearsReport.BucketTotals::bucket,
                            StaffLoanArrearsReport.BucketTotals::loans, b -> b.outstanding().toPlainString())
                    .containsExactly(
                            tuple(StaffLoanArrearsBucket.NOT_DUE, 1, "100.00"),
                            tuple(StaffLoanArrearsBucket.DAYS_1_TO_30, 2, "370.00"),
                            tuple(StaffLoanArrearsBucket.DAYS_31_TO_60, 1, "150.00"),
                            tuple(StaffLoanArrearsBucket.DAYS_61_TO_90, 0, "0.00"),
                            tuple(StaffLoanArrearsBucket.OVER_90_DAYS, 1, "200.00"));
        });
    }

    @Test
    @DisplayName("the spreadsheet has a row per loan, safe to open, named for the day")
    void csv() {
        loan(127, "E1023", "Mutasa, Blessing", StaffLoanStatus.DISBURSED, "150.00", LocalDate.of(2026, 10, 20),
                null);
        loan(131, "E1031", "=HYPERLINK(\"x\")", StaffLoanStatus.WRITTEN_OFF, "90.00", LocalDate.of(2026, 9, 20),
                null);

        StaffLoanArrearsService.Csv csv = service.csv();

        assertThat(csv.fileName()).isEqualTo("staff-loan-arrears-2026-12-02.csv");
        String[] rows = csv.content().split("\r\n");
        assertThat(rows).hasSize(3);
        assertThat(rows[0]).startsWith("reference,employeeNumber,fullName,department,employmentStatus,grade,msisdn,");
        assertThat(rows[1]).startsWith("SGL-2026-000131,E1031,\"'=HYPERLINK(\"\"x\"\")\",Retail Banking,ACTIVE,C4,")
                .contains(",WRITTEN_OFF,USD,90.00,90.00,", ",73,DAYS_61_TO_90,true,false,,");
        assertThat(rows[2]).startsWith("SGL-2026-000127,E1023,\"Mutasa, Blessing\",")
                .contains(",2026-09-18T10:12:40+02:00,2026-10-20,43,DAYS_31_TO_60,true,true,,");
    }

    @Test
    @DisplayName("the morning email goes once to each Credit and Human Capital address, naming references and employee"
            + " numbers but never the borrowers, and is audited")
    void emails() {
        story();
        credit.add(user("credit1@innbucks.co.zw"));
        credit.add(user(" Shared@InnBucks.co.zw "));
        humanCapital.add(user("shared@innbucks.co.zw"));
        humanCapital.add(user("hc1@innbucks.co.zw"));
        humanCapital.add(user(null));

        service.sendDaily();

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(3)).sendEmail(to.capture(), eq("Staff Grocery Loans not recovered in full,"
                + " 2 Dec 2026: 5 loans, USD 820.00"), body.capture());
        assertThat(to.getAllValues()).containsExactly("credit1@innbucks.co.zw", "shared@innbucks.co.zw",
                "hc1@innbucks.co.zw");
        String text = body.getValue();
        assertThat(text).contains(
                "USD: 5 loans, 820.00 outstanding. 4 past the due date, 2 escalated to Credit, 1 written off, 2"
                        + " borrowers no longer active.",
                "Escalated to Credit, unpaid 30 days or more after the due date:\n"
                        + "SGL-2026-000127, employee E1023, 43 days past the due date (20 Oct 2026), USD 150.00"
                        + " outstanding.\n"
                        + "SGL-2026-000130, employee E1030, 30 days past the due date (2 Nov 2026), USD 120.00"
                        + " outstanding.\n",
                "SGL-2026-000088, employee E1088, written off, USD 200.00 outstanding.",
                "SGL-2026-000151, employee E1001, 12 days past the due date (20 Nov 2026), USD 250.00 outstanding;"
                        + " RESIGNED: recover from terminal benefits.",
                "SGL-2026-000160, employee E1100, due 20 Dec 2026, USD 100.00 outstanding; SUSPENDED: Credit decides"
                        + " its due date.",
                "with names and departments, is the Staff Grocery Loan arrears report in the InnBucks Lending portal");
        assertThat(text).doesNotContain("Nyasha", "Dube", "Gumbo", "Mutasa", "Ruvimbo", "Ncube", "263773");

        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        assertThat(audit.getValue().build()).satisfies(log -> {
            assertThat(log.getEventType()).isEqualTo(StaffLoanArrearsService.SENT);
            assertThat(log.getEntityId()).isEqualTo("2026-12-02");
            assertThat(log.getActorId()).isEqualTo(StaffLoanArrearsService.SYSTEM_ACTOR);
            assertThat(log.getDetail()).isEqualTo("loans:5;escalated:2;recipients:3");
        });
    }

    @Test
    @DisplayName("with nothing in arrears the email still goes, saying so; one address failing does not stop the rest")
    void nothingInArrears() {
        credit.add(user("credit1@innbucks.co.zw"));
        humanCapital.add(user("hc1@innbucks.co.zw"));
        doThrow(new IllegalStateException("mail queue down")).when(notifications)
                .sendEmail(eq("credit1@innbucks.co.zw"), anyString(), anyString());

        service.sendDaily();

        verify(notifications).sendEmail("hc1@innbucks.co.zw", "Staff Grocery Loans, 2 Dec 2026: none in arrears",
                "No paid-out Staff Grocery Loan is past its due date, written off, or owed by a borrower who is no"
                        + " longer active, as at 2 Dec 2026.");
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        assertThat(audit.getValue().build().getDetail()).isEqualTo("loans:0;escalated:0;recipients:1");
    }

    @Test
    @DisplayName("nobody to send to: nothing is sent or audited; the report itself failing never throws")
    void nobodyOrNothing() {
        story();
        service.sendDaily();
        verifyNoInteractions(notifications, auditService);

        when(loanRepository.findNotRecovered(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        credit.add(user("credit1@innbucks.co.zw"));
        service.sendDaily();
        verifyNoInteractions(notifications, auditService);
    }

    @Test
    @DisplayName("the email's schedule must be a cron expression: a bad, blank or missing one fails the boot")
    void scheduleSetting() {
        StaffLoanProperties settings = new StaffLoanProperties();
        assertThat(settings.getArrearsReportCron()).isEqualTo("0 0 7 * * *");
        assertThat(settings.isArrearsReportCronValid()).isTrue();
        for (String bad : new String[]{"every morning", " ", null}) {
            settings.setArrearsReportCron(bad);
            assertThat(settings.isArrearsReportCronValid()).as(String.valueOf(bad)).isFalse();
        }
    }

    @Test
    @DisplayName("a long report lists the first loans in the email and points to the portal for the rest")
    void longReport() {
        IntStream.rangeClosed(1, StaffLoanArrearsService.EMAIL_LINE_LIMIT + 2).forEach(i -> loan(1000 + i,
                "E" + (5000 + i), "Borrower " + i, StaffLoanStatus.DISBURSED, "10.00", LocalDate.of(2026, 11, 20),
                null));

        String body = StaffLoanArrearsService.body(service.report());

        assertThat(body).contains("... and 2 more.");
        assertThat(body.lines().filter(line -> line.startsWith("SGL-2026-")).count())
                .as("the escalated list is empty, and the full list capped").isEqualTo(
                        StaffLoanArrearsService.EMAIL_LINE_LIMIT);
    }
}
