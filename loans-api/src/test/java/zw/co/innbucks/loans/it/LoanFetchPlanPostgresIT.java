package zw.co.innbucks.loans.it;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.DisbursementResponse;
import zw.co.innbucks.loans.core.NdasendaLodgementJob;
import zw.co.innbucks.loans.core.StartupTask;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.channel.ChannelRepository;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionGroupRepository;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.disbursements.BookingFailureKind;
import zw.co.innbucks.loans.core.disbursements.InnbucksDisbursementService;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanBookingJob;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatusJob;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatusResponse;
import zw.co.innbucks.loans.core.ledger.DisbursementLedger;
import zw.co.innbucks.loans.core.loan.Address;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.DisbursementStatus;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.loan.LoanDisbursementRepository;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.PayslipDeduction;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.ndasenda.LoanApprovalService;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationRepository;
import zw.co.innbucks.loans.core.notice.LoanNotificationSender;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.notice.OutgoingNotice;
import zw.co.innbucks.loans.core.saga.LoanSagaOrchestrator;
import zw.co.innbucks.loans.core.saga.LoanSagaRepository;
import zw.co.innbucks.loans.core.saga.LoanSagaTransitionService;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatch;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchSource;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchStatus;
import zw.co.innbucks.loans.core.staff.StaffRegisterRow;
import zw.co.innbucks.loans.core.staff.StaffRegisterRowAction;
import zw.co.innbucks.loans.core.staff.StaffRegisterRowOutcome;
import zw.co.innbucks.loans.core.staff.StaffRegisterRowRepository;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.core.workflow.AssignmentMode;
import zw.co.innbucks.loans.core.workflow.CheckpointGate;
import zw.co.innbucks.loans.core.workflow.CreateCheckpointStageRequest;
import zw.co.innbucks.loans.core.workflow.HoldPoint;
import zw.co.innbucks.loans.core.workflow.WorkflowEscalationService;
import zw.co.innbucks.loans.core.workflow.WorkflowStageService;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@code Loan}'s merchant, originator and channel are LAZY (and so are its payslip deductions), and
 * {@code spring.jpa.open-in-view} is false: a path that reads one must fetch it in its query or read it inside a
 * transaction, or it throws {@code LazyInitializationException} — a 500 on an endpoint, or, on a job that books or
 * pays with no transaction open, a booking held AMBIGUOUS or a payout SMS that never goes. Every path that reads an
 * association is driven here end to end against real Postgres (real HTTP, a real token, the real security chain),
 * so a regression fails a test, and the read paths' statement counts are pinned from Hibernate's {@link Statistics}
 * ({@code hibernate.generate_statistics}, on in the {@code it} profile only): a list costs the same at 3 loans and at
 * 12, each loan with its own merchant, originator and channel.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "it"})
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoanFetchPlanPostgresIT {

    private static final String BASE = "/lending/v1";
    private static final String CHECKPOINT = "IT_CREDIT_CHECK";
    private static final int CHANNELS = 12;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = ItPostgres.createDatabase("loans_fetch_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ItPostgres::user);
        registry.add("spring.datasource.password", ItPostgres::password);
    }

    @LocalServerPort
    int port;

    @Autowired private EntityManagerFactory emf;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private LoanRepository loanRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private CommissionGroupRepository commissionGroupRepository;
    @Autowired private WorkflowStageService workflowStageService;
    @Autowired private WorkflowEscalationService workflowEscalationService;
    @Autowired private CheckpointGate checkpointGate;
    @Autowired private DeductionCancellationService deductionCancellationService;
    @Autowired private LoanDisbursementRepository loanDisbursementRepository;
    @Autowired private AuditService auditService;
    @Autowired private LoanNotificationService loanNotificationService;
    @Autowired private LoanNotificationRepository loanNotificationRepository;
    @Autowired private DisbursementLedger disbursementLedger;
    @Autowired private LoanApprovalService loanApprovalService;
    @Autowired private LoanBatchService loanBatchService;
    @Autowired private LoanSagaRepository sagaRepository;
    @Autowired private LoanSagaTransitionService sagaTransitionService;
    @Autowired private StaffRegisterBatchRepository staffRegisterBatchRepository;
    @Autowired private StaffRegisterRowRepository staffRegisterRowRepository;

    /** The real InnBucks client, so a job reads exactly what the booking and payout read; stubbed per test. */
    @MockitoSpyBean private InnbucksDisbursementService innbucks;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Long> measured = new LinkedHashMap<>();
    private TransactionTemplate tx;
    private Statistics stats;
    private String adminToken;

    /** Seeded once for the class (the test methods run in order and build on it). */
    private static final List<Channel> channels = new ArrayList<>();
    private static int seeded;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).as("hibernate.generate_statistics in the it profile").isTrue();
        adminToken = jwtService.generateToken(superAdmin());
        if (channels.isEmpty()) {
            for (int i = 1; i <= CHANNELS; i++) {
                Merchant merchant = merchant("CHM" + i);
                channels.add(channelRepository.save(Channel.builder().channelId("IT-CH-" + i).name("Channel " + i)
                        .systemUser(user("channel-system-" + i, UserGroup.AGENTS, merchant)).build()));
            }
        }
    }

    @AfterEach
    void report() {
        measured.forEach((path, count) -> System.out.println("[statements] " + path + " = " + count));
    }

    // ── Read paths ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("every read path renders the associations, and a list costs the same at 3 loans as at 12")
    void readPathsRenderTheAssociations_atACostIndependentOfSize() throws Exception {
        // A checkpoint before Credit that applies by channel: its queue reads each loan's channel.
        Set<String> channelIds = new LinkedHashSet<>(channels.stream().map(Channel::getChannelId).toList());
        workflowStageService.create(CreateCheckpointStageRequest.builder()
                .code(CHECKPOINT).holdPoint(HoldPoint.BEFORE_CREDIT_APPROVAL).name("IT credit check")
                .channels(channelIds).assignment(AssignmentMode.OPTIONAL)
                .viewRoles(Set.of(UserGroup.CREDIT_MANAGER)).workRoles(Set.of(UserGroup.CREDIT_MANAGER))
                .assignRoles(Set.of(UserGroup.CREDIT_MANAGER)).targetHours(1).escalationHours(1)
                .escalateTo(Set.of(UserGroup.CREDIT_MANAGER)).notifyAssignee(false).build());

        List<Long> small = seed(3, this::awaitingCredit);
        measureReadPaths("[N=3]", small.getFirst(), 3);
        List<Long> large = seed(9, this::awaitingCredit);
        measureReadPaths("[N=12]", large.getFirst(), 12);

        for (String path : List.of("GET /loans", "GET /loans/pending-credit-decision",
                "GET /work-queues/CREDIT_DECISION/items", "GET /work-queues/" + CHECKPOINT + "/items",
                "GET /work-queues", "GET /reports/workflow-pipeline", "GET /loans/{id}",
                "GET /loans/{id}/credit-workbench", "CheckpointGate.withoutHeld", "lock a loan (job claim)")) {
            assertThat(measured.get("[N=12] " + path)).as(path + ": the same at 12 loans as at 3")
                    .isEqualTo(measured.get("[N=3] " + path));
        }
    }

    private void measureReadPaths(String size, Long oneLoan, int loans) throws Exception {
        JsonNode list = measure(size + " GET /loans", BASE + "/loans?size=100").path("data").path("items");
        assertThat(list).hasSize(loans);
        assertThat(list).allSatisfy(row -> {
            assertThat(row.path("merchantName").asString()).startsWith("Merchant ");
            assertThat(row.path("createdByName").asString()).startsWith("Agent ");
            assertThat(row.path("channelName").asString()).startsWith("Channel ");
        });
        JsonNode queue = measure(size + " GET /loans/pending-credit-decision",
                BASE + "/loans/pending-credit-decision?size=100").path("data").path("items");
        assertThat(queue).hasSize(loans);
        assertThat(queue).allSatisfy(row -> assertThat(row.path("creditTurnaround").isObject()).isTrue());

        JsonNode items = measure(size + " GET /work-queues/CREDIT_DECISION/items",
                BASE + "/work-queues/CREDIT_DECISION/items").path("data");
        assertThat(items).hasSize(loans);
        assertThat(items).allSatisfy(row -> assertThat(row.path("channelName").asString()).startsWith("Channel "));
        JsonNode held = measure(size + " GET /work-queues/" + CHECKPOINT + "/items",
                BASE + "/work-queues/" + CHECKPOINT + "/items").path("data");
        assertThat(held).hasSize(loans);
        measure(size + " GET /work-queues", BASE + "/work-queues");
        measure(size + " GET /reports/workflow-pipeline", BASE + "/reports/workflow-pipeline");

        JsonNode detail = measure(size + " GET /loans/{id}", BASE + "/loans/" + oneLoan).path("data");
        assertThat(detail.path("merchantName").asString()).startsWith("Merchant ");
        assertThat(detail.path("channelName").asString()).startsWith("Channel ");
        assertThat(detail.path("payslipDeductions")).hasSize(2);
        JsonNode workbench = measure(size + " GET /loans/{id}/credit-workbench",
                BASE + "/loans/" + oneLoan + "/credit-workbench").path("data");
        assertThat(workbench.isObject()).isTrue();

        // What the lodgement and booking jobs ask with no transaction open, over every loan seeded so far.
        List<Long> ids = loanRepository.findAll().stream().map(Loan::getId).sorted().toList();
        measured.put(size + " CheckpointGate.withoutHeld",
                count(() -> checkpointGate.withoutHeld(HoldPoint.BEFORE_CREDIT_APPROVAL, ids)));
        // What every job's claim does: lock one loan and read its status columns.
        measured.put(size + " lock a loan (job claim)", count(() -> tx.execute(status ->
                loanRepository.findByIdForUpdate(oneLoan).map(Loan::getLoanApprovalStatus).orElseThrow())));
    }

    // ── Paths that hand a loan past its transaction ──────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("booking: the merchant is read after the claim commits, so InnBucks being unreachable reads as not"
            + " sent (claim released), never as an AMBIGUOUS booking")
    void bookingReadsTheMerchantAfterTheClaim() {
        // A checkpoint before booking holding the first loan's channel: CheckpointGate reads the chunk's channels.
        workflowStageService.create(CreateCheckpointStageRequest.builder()
                .code("IT_BOOKING_CHECK").holdPoint(HoldPoint.BEFORE_BOOKING).name("IT booking check")
                .channels(Set.of(channelFor(seeded + 1).getChannelId())).assignment(AssignmentMode.NONE)
                .viewRoles(Set.of(UserGroup.FINANCE)).workRoles(Set.of(UserGroup.FINANCE))
                .assignRoles(Set.of(UserGroup.FINANCE)).targetHours(1)
                .escalateTo(Set.of()).notifyAssignee(false).build());
        List<Long> due = seed(3, this::readyToBook);

        new LoanBookingJob(innbucks, loanRepository, deductionCancellationService, loanDisbursementRepository,
                auditService, loanNotificationService, checkpointGate, transactionManager, 30)
                .processLoanAccountCreation();

        Loan held = loanRepository.findById(due.get(0)).orElseThrow();
        assertThat(held.getDisbursementStatusMessage()).as("held at the checkpoint: never claimed").isNull();
        Loan tried = loanRepository.findById(due.get(1)).orElseThrow();
        assertThat(tried.getBookingFailureKind()).as("not AMBIGUOUS: nothing was sent").isNull();
        assertThat(tried.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.PENDING);
        assertThat(tried.getBookingClaimedAt()).as("claim released for a later run").isNull();
        assertThat(tried.getDisbursementStatusMessage()).endsWith("will retry");
        // InnBucks is unreachable, so the run stops at the first loan it tries.
        assertThat(loanRepository.findById(due.get(2)).orElseThrow().getDisbursementStatusMessage()).isNull();
    }

    @Test
    @Order(3)
    @DisplayName("the disbursement status job reads its chunk with the merchant, and the payout SMS names it")
    void statusJobNamesTheMerchantInThePayoutSms() {
        List<Long> booked = seed(3, this::bookedAwaitingPayout);
        doReturn(LoanDisbursementStatusResponse.builder().responseCode("000").success(true)
                .status(LoanDisbursementStatus.SUCCESS).build()).when(innbucks).checkLoanDisbursementStatus(any());
        // The real notification service over a sender that only records: the SMS is worded by the job itself.
        LoanNotificationSender sender = mock(LoanNotificationSender.class);
        LoanNotificationService notices = new LoanNotificationService(sender, loanNotificationRepository,
                loanRepository);

        new LoanDisbursementStatusJob(innbucks, loanRepository, notices, deductionCancellationService, auditService,
                disbursementLedger, transactionManager, new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                .processLoanDisbursementStatus();

        for (Long id : booked) {
            assertThat(loanRepository.findById(id).orElseThrow().getDisbursementStatus())
                    .isEqualTo(LoanDisbursementStatus.SUCCESS);
        }
        ArgumentCaptor<OutgoingNotice> sms = ArgumentCaptor.forClass(OutgoingNotice.class);
        verify(sender, atLeastOnce()).deliver(sms.capture());
        assertThat(sms.getAllValues()).hasSize(3).allSatisfy(notice -> {
            assertThat(notice.notice()).isEqualTo(LoanNotice.PAID);
            assertThat(notice.message()).contains("paid to Merchant ");
        });
    }

    @Test
    @Order(4)
    @DisplayName("a manual recovery payout over HTTP: the customer SMS, worded after the commit, names the merchant")
    void manualPayoutNamesTheMerchantAfterTheCommit() throws Exception {
        Long loanId = seed(1, this::refusedBooking).getFirst();
        doReturn(DisbursementResponse.builder().status(DisbursementStatus.SUCCESS).approvalCode("AUTH-1")
                .message("Paid").build()).when(innbucks).disburseFunds(any());

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(BASE + "/loans/" + loanId + "/disbursements"))
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("data").path("outcome").asString()).isEqualTo("DISBURSED");
        assertThat(loanRepository.findById(loanId).orElseThrow().getDisbursementStatus())
                .isEqualTo(LoanDisbursementStatus.SUCCESS);
    }

    @Test
    @Order(5)
    @DisplayName("escalation reads a checkpoint's queue (the loans' channels) with no transaction of the caller's")
    void escalationReadsTheCheckpointQueue() {
        // The checkpoint of test 1 has held its loans for three hours; it escalates after one.
        jdbc.update("UPDATE workflow_stages SET active_since = ? WHERE code = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(3), CHECKPOINT);

        int escalated = workflowEscalationService.escalateOverdue();

        assertThat(escalated).isGreaterThanOrEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_items WHERE stage_code = ? AND escalated_at IS NOT"
                + " NULL", Long.class, CHECKPOINT)).isEqualTo(12);
    }

    @Test
    @Order(6)
    @DisplayName("lodgement: CheckpointGate reads the chunk's channels with no transaction open; held loans wait")
    void lodgementSkipsHeldLoans() {
        // A checkpoint before lodgement holding the first loan's channel.
        workflowStageService.create(CreateCheckpointStageRequest.builder()
                .code("IT_LODGEMENT_CHECK").holdPoint(HoldPoint.BEFORE_LODGEMENT).name("IT lodgement check")
                .channels(Set.of(channelFor(seeded + 1).getChannelId())).assignment(AssignmentMode.NONE)
                .viewRoles(Set.of(UserGroup.FINANCE)).workRoles(Set.of(UserGroup.FINANCE))
                .assignRoles(Set.of(UserGroup.FINANCE)).targetHours(1)
                .escalateTo(Set.of()).notifyAssignee(false).build());
        List<Long> fresh = seed(3, this::newApplication);

        new NdasendaLodgementJob(loanApprovalService, loanRepository, loanNotificationService, loanBatchService,
                auditService, checkpointGate, transactionManager, 3, 10, 30).processSsbApprovals();

        List<Loan> loans = fresh.stream().map(id -> loanRepository.findById(id).orElseThrow()).toList();
        assertThat(loans).allSatisfy(loan -> {
            assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
            assertThat(loan.getLodgementClaimedAt()).isNull();
        });
        assertThat(loans.get(0).getApprovalAttempt()).as("held at the checkpoint: never tried").isNull();
        // Ndasenda is unreachable: the first loan tried is not sent, and the run stops there.
        assertThat(loans.get(1).getApprovalAttempt()).isEqualTo(1);
        assertThat(loans.get(2).getApprovalAttempt()).isNull();
    }

    @Test
    @Order(7)
    @DisplayName("the saga reconciler reads its candidates a chunk at a time and opens a saga for every loan")
    void sagaReconcilerCoversEveryLoan() {
        new LoanSagaOrchestrator(sagaRepository, sagaTransitionService).reconcile();

        assertThat(sagaRepository.count()).isEqualTo(loanRepository.count());
    }

    // ── Insert batching (V33) ────────────────────────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("a register upload's rows insert in JDBC batches: statements grow by the block, not by the row")
    void registerRowsInsertInBatches() {
        for (int rows : List.of(60, 240)) {
            long statements = count(() -> tx.execute(status -> {
                StaffRegisterBatch batch = staffRegisterBatchRepository.save(StaffRegisterBatch.builder()
                        .source(StaffRegisterBatchSource.MANUAL).status(StaffRegisterBatchStatus.PENDING)
                        .submittedBy("it").submittedAt(LocalDateTime.now(ZoneOffset.UTC))
                        .totalRows(rows).stagedRows(rows).rejectedRows(0).build());
                List<StaffRegisterRow> staged = new ArrayList<>();
                for (int row = 1; row <= rows; row++) {
                    staged.add(StaffRegisterRow.builder().batchId(batch.getId()).rowNumber(row)
                            .outcome(StaffRegisterRowOutcome.STAGED).action(StaffRegisterRowAction.CREATE)
                            .employeeNumber("E" + row).build());
                }
                return staffRegisterRowRepository.saveAll(staged);
            }));
            measured.put("[rows=" + rows + "] StaffRegisterRowRepository.saveAll (with its batch)", statements);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT id) FROM staff_register_rows WHERE row_number <= ?",
                    Long.class, rows)).isGreaterThanOrEqualTo(rows);
        }
        // The batch row, then per 50 rows one nextval and one JDBC batch of INSERTs: 240 rows cost ~10, not ~240.
        long blocks = (240 + 49) / 50;
        assertThat(measured.get("[rows=240] StaffRegisterRowRepository.saveAll (with its batch)"))
                .isLessThanOrEqualTo(1 + 2 * blocks + 1);
    }

    // ── Seeding ──────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code count} loans, each with a merchant, an originator and a channel of its own; their ids. */
    private List<Long> seed(int count, java.util.function.Consumer<Loan.LoanBuilder> state) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = ++seeded;
            Merchant merchant = merchant("M" + n);
            User agent = user("agent-" + n, UserGroup.AGENTS, merchant);
            Loan.LoanBuilder loan = Loan.builder()
                    .principal(new BigDecimal("500.00")).disbursedAmount(new BigDecimal("450.00")).tenor(6)
                    .monthlyInstallment(new BigDecimal("98.50")).grossedMonthlyDeduction(new BigDecimal("98.50"))
                    .firstName("Rudo").lastName("Moyo " + n)
                    .ecNumber(String.format("%07dA", 1_000_000 + n)).nationalIdNumber("63-" + (100000 + n) + "A63")
                    .mobileNumber(String.format("26377%07d", n)).walletNumber(String.format("26377%07d", n))
                    .dateOfBirth(LocalDate.of(1990, 1, 1)).address(address())
                    .payslipDeductions(new ArrayList<>(List.of(new PayslipDeduction("ZIMRA PAYE", new BigDecimal("40")),
                            new PayslipDeduction("Medical aid", new BigDecimal("12")))))
                    .merchant(merchant).createdByUser(agent).createdBy(agent.getUsername()).channel(channelFor(n));
            state.accept(loan);
            ids.add(loanRepository.save(loan.build()).getId());
        }
        return ids;
    }

    private Channel channelFor(int n) {
        return channels.get((n - 1) % CHANNELS);
    }

    private void awaitingCredit(Loan.LoanBuilder loan) {
        loan.loanApprovalStatus(LoanApprovalStatus.APPROVED).internalApprovalStatus(InternalApprovalStatus.PENDING)
                .loanAccountStatus(LoanAccountStatus.PENDING).disbursementStatus(LoanDisbursementStatus.PENDING)
                .dateApproved(LocalDateTime.now(ZoneOffset.UTC).minusHours(2));
    }

    private void readyToBook(Loan.LoanBuilder loan) {
        loan.loanApprovalStatus(LoanApprovalStatus.APPROVED).internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .internalApprovalBy("credit.officer").internalApprovalDate(LocalDateTime.now(ZoneOffset.UTC))
                .loanAccountStatus(LoanAccountStatus.PENDING).disbursementStatus(LoanDisbursementStatus.PENDING)
                .approvedDisbursementType(DisbursementType.MERCHANT_MOBILE_WALLET)
                .approvedSettlementAccount("263771000001");
    }

    private void bookedAwaitingPayout(Loan.LoanBuilder loan) {
        readyToBook(loan);
        loan.loanAccountStatus(LoanAccountStatus.CREATED).disbursementStatus(LoanDisbursementStatus.PENDING);
    }

    private void refusedBooking(Loan.LoanBuilder loan) {
        readyToBook(loan);
        loan.loanAccountStatus(LoanAccountStatus.FAILED).disbursementStatus(LoanDisbursementStatus.FAILED)
                .bookingFailureKind(BookingFailureKind.REFUSED);
    }

    private void newApplication(Loan.LoanBuilder loan) {
        loan.loanApprovalStatus(LoanApprovalStatus.NEW).internalApprovalStatus(InternalApprovalStatus.PENDING)
                .loanAccountStatus(LoanAccountStatus.PENDING).disbursementStatus(LoanDisbursementStatus.PENDING);
    }

    private static Address address() {
        Address address = new Address();
        address.setStreet("12 Samora Machel Ave");
        address.setCity("Harare");
        address.setCountry("Zimbabwe");
        return address;
    }

    private Merchant merchant(String code) {
        CommissionGroup group = commissionGroupRepository.findByNameIgnoreCase(StartupTask.ZERO_BASED_DEFAULT)
                .orElseThrow();
        return merchantRepository.save(Merchant.builder().merchantCode("IT-" + code).companyName("Merchant " + code)
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).accountNumber("263771000001")
                .commissionGroup(group).commissionStructure(CommissionStructure.MERCHANT_DEFINED).build());
    }

    private User user(String username, UserGroup group, Merchant merchant) {
        User user = new User();
        user.setUsername(username);
        user.setExternalSystemId(UUID.randomUUID().toString());
        user.setFirstName("Agent");
        user.setLastName(username);
        user.setGroups(new java.util.HashSet<>(Set.of(group)));
        user.setMerchant(merchant);
        user.setTemporaryPassword(false);
        return userRepository.save(user);
    }

    private User superAdmin() {
        return userRepository.findByUsername("it-super-admin")
                .orElseGet(() -> user("it-super-admin", UserGroup.SUPER_ADMIN, merchant("ADMIN")));
    }

    // ── HTTP and counting ────────────────────────────────────────────────────────────────────────────────────

    private JsonNode measure(String name, String path) throws Exception {
        stats.clear();
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).GET());
        long count = stats.getPrepareStatementCount();
        assertThat(response.statusCode()).as("%s -> %s", name, response.body()).isEqualTo(200);
        measured.put(name, count);
        return json.readTree(response.body());
    }

    private long count(Supplier<?> work) {
        stats.clear();
        work.get();
        return stats.getPrepareStatementCount();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.header("Authorization", "Bearer " + adminToken).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
