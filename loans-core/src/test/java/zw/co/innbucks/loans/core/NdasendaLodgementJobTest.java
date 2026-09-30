package zw.co.innbucks.loans.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.UnknownContentTypeException;
import zw.co.innbucks.loans.core.notice.LoanNotificationSender;
import zw.co.innbucks.loans.core.notice.LoanNotificationRepository;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.PayslipReviewStatus;
import zw.co.innbucks.loans.core.ndasenda.LoanApprovalResponse;
import zw.co.innbucks.loans.core.ndasenda.LoanApprovalService;
import zw.co.innbucks.loans.core.ndasenda.LodgementException;
import zw.co.innbucks.loans.core.ndasenda.NdasendaAuthService;
import zw.co.innbucks.loans.core.ndasenda.NdasendaDeductionBatch;
import zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl;
import zw.co.innbucks.loans.core.ndasenda.NdasendaParameters;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Lodging a loan with Ndasenda puts an irreversible stop order on a civil servant's salary. The job
 * used to run every NEW loan inside one transaction with no claim, mark ANY failure FAILED, and resend
 * the POST whenever the answer could not be read — so a rollback, a second instance or an unreadable
 * answer each lodged a loan twice, and a timeout gave up on a deduction that may be live. These pin
 * the outcomes: claimed before the call, sent once, and settled per loan by what is actually known.
 *
 * <p>Most cases drive the real {@link NdasendaLoanApprovalServiceImpl} over a mocked
 * {@link RestTemplate}, so "sent once" is counted at the HTTP client.</p>
 */
class NdasendaLodgementJobTest {

    private static final String DEDUCTIONS = "https://ndasenda.test/api/v1/deductions/requests";
    private static final String EC_NUMBER = "1234567A";
    private static final String NATIONAL_ID = "63-1234567A63";

    private RestTemplate restTemplate;
    private NdasendaAuthService auth;
    private LoanRepository loanRepository;
    private LoanNotificationService loanNotificationService;
    private LoanBatchService loanBatchService;
    private AuditService auditService;
    private RecordingTransactions transactions;
    private final Map<Long, Loan> loans = new LinkedHashMap<>();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        auth = mock(NdasendaAuthService.class);
        when(auth.getAccessToken()).thenReturn("tok-abc");
        loanRepository = mock(LoanRepository.class);
        loanNotificationService = mock(LoanNotificationService.class);
        loanBatchService = mock(LoanBatchService.class);
        auditService = mock(AuditService.class);
        transactions = new RecordingTransactions();

        // The repository over an in-memory table: the due query applies the same rule as the JPQL.
        when(loanRepository.findIdsDueForLodgement(any())).thenAnswer(call -> {
            LocalDateTime now = call.getArgument(0);
            return loans.values().stream()
                    .filter(l -> l.getLoanApprovalStatus() == LoanApprovalStatus.NEW && l.getLodgementClaimedAt() == null
                            && (l.getNextLodgementAttemptAt() == null || !l.getNextLodgementAttemptAt().isAfter(now)))
                    .map(Loan::getId).toList();
        });
        when(loanRepository.findIdsWithLodgementClaimedBefore(any())).thenAnswer(call -> {
            LocalDateTime cutoff = call.getArgument(0);
            return loans.values().stream()
                    .filter(l -> l.getLoanApprovalStatus() == LoanApprovalStatus.NEW && l.getLodgementClaimedAt() != null
                            && l.getLodgementClaimedAt().isBefore(cutoff))
                    .map(Loan::getId).toList();
        });
        when(loanRepository.findByIdForUpdate(anyLong())).thenAnswer(call -> Optional.ofNullable(loans.get((Long) call.getArgument(0))));
        when(loanRepository.save(any(Loan.class))).thenAnswer(call -> call.getArgument(0));

        logs = new ListAppender<>();
        logs.start();
        logger(NdasendaLodgementJob.class).addAppender(logs);
        logger(NdasendaLoanApprovalServiceImpl.class).addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        logger(NdasendaLodgementJob.class).detachAppender(logs);
        logger(NdasendaLoanApprovalServiceImpl.class).detachAppender(logs);
    }

    private static Logger logger(Class<?> type) {
        return (Logger) LoggerFactory.getLogger(type);
    }

    private Loan newLoan(long id) {
        Loan loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.NEW)
                .ecNumber(EC_NUMBER)
                .nationalIdNumber(NATIONAL_ID)
                .grossedMonthlyDeduction(new BigDecimal("98.50"))
                .tenor(6)
                .mobileNumber("0772123123")
                .build();
        loan.setId(id);
        loans.put(id, loan);
        return loan;
    }

    private NdasendaLodgementJob job(LoanApprovalService service) {
        return job(service, loanNotificationService);
    }

    private NdasendaLodgementJob job(LoanApprovalService service, LoanNotificationService notifications) {
        return new NdasendaLodgementJob(service, loanRepository, notifications, loanBatchService,
                auditService, transactions, 3, 10, 30);
    }

    /** The job over the real Ndasenda client, talking to the mocked RestTemplate. */
    private NdasendaLodgementJob ndasendaJob() {
        return job(ndasenda());
    }

    private NdasendaLoanApprovalServiceImpl ndasenda() {
        NdasendaParameters props = new NdasendaParameters();
        props.setDeductionRequestsEndpoint(DEDUCTIONS);
        props.setDeductionCode("DC01");
        props.setSecurityCode("SEC01");
        return new NdasendaLoanApprovalServiceImpl(restTemplate, auth, props, loanRepository, loanBatchService,
                loanNotificationService, auditService, mock(DeductionCancellationService.class), new MarketTimeZone("ZW"));
    }

    /** Ndasenda's answer to each lodgement POST, chosen by the loan reference it carries. */
    private void ndasendaAnswers(Function<String, ResponseEntity<NdasendaDeductionBatch>> answer) {
        when(restTemplate.exchange(eq(DEDUCTIONS), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(NdasendaDeductionBatch.class)))
                .thenAnswer(call -> {
                    HttpEntity<?> entity = call.getArgument(2);
                    NdasendaDeductionBatch batch = (NdasendaDeductionBatch) entity.getBody();
                    return answer.apply(batch.getDeductions().get(0).getReference());
                });
    }

    private static ResponseEntity<NdasendaDeductionBatch> accepted(String batchId) {
        return ResponseEntity.ok(NdasendaDeductionBatch.builder().id(batchId).build());
    }

    private int lodgementPosts() {
        return (int) mockingDetails(restTemplate).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("exchange")
                        && DEDUCTIONS.equals(call.getArgument(0)) && call.getArgument(1) == HttpMethod.POST)
                .count();
    }

    private List<AuditLog> audits() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return (HttpClientErrorException) HttpClientErrorException.create(status, status.getReasonPhrase(),
                HttpHeaders.EMPTY, ("{\"error\":\"" + NATIONAL_ID + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an accepted lodgement is PROCESSING with Ndasenda's batch id, sent once, batch listed, customer told")
    void acceptedLodgementIsProcessingWithBatchId() {
        Loan loan = newLoan(42);
        ndasendaAnswers(reference -> accepted("BATCH-20260929-01"));

        ndasendaJob().processSsbApprovals();

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getBatchNumber()).isEqualTo("BATCH-20260929-01");
        assertThat(loan.getLodgementClaimedAt()).isNotNull();
        assertThat(loan.getNextLodgementAttemptAt()).isNull();
        assertThat(loan.getApprovalAttempt()).isNull();
        assertThat(loan.getRepaymentStartDate()).isNotNull();
        assertThat(lodgementPosts()).isEqualTo(1);
        verify(loanBatchService).save("BATCH-20260929-01");
        // Told it is with SSB (FR-SSB-016), once the settle commits.
        verify(loanNotificationService).notify(loan, LoanNotice.SENT_TO_SSB);
        verifyNoInteractions(auditService);
        // The claim, then the settle, each committed on its own.
        assertThat(transactions.events).containsExactly("begin", "commit", "begin", "commit");
        assertThat(transactions.propagations).containsOnly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("an SMS failure after a clean lodgement leaves it PROCESSING - it used to FAIL a live deduction")
    void smsFailureAfterLodgementKeepsItProcessing() {
        Loan loan = newLoan(42);
        ndasendaAnswers(reference -> accepted("BATCH-20260929-01"));
        // The real notification service, over a sender that fails: the notice is raised inside the settle.
        LoanNotificationSender failing = mock(LoanNotificationSender.class);
        doThrow(new IllegalStateException("SMS gateway unreachable")).when(failing).deliver(any());
        LoanNotificationService notifications = new LoanNotificationService(failing,
                mock(LoanNotificationRepository.class), loanRepository);

        job(ndasenda(), notifications).processSsbApprovals();

        verify(failing).deliver(any());
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getBatchNumber()).isEqualTo("BATCH-20260929-01");
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        assertThat(lodgementPosts()).isEqualTo(1);
    }

    @Test
    @DisplayName("one loan refused by Ndasenda is FAILED on its own; the next loan is still lodged and committed")
    void aRefusedLoanDoesNotAffectTheNext() {
        Loan refused = newLoan(41);
        Loan lodged = newLoan(42);
        ndasendaAnswers(reference -> {
            if (reference.equals("000000041")) {
                throw clientError(HttpStatus.BAD_REQUEST);
            }
            return accepted("BATCH-20260929-02");
        });

        ndasendaJob().processSsbApprovals();

        assertThat(refused.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.FAILED);
        assertThat(lodged.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(lodged.getBatchNumber()).isEqualTo("BATCH-20260929-02");
        assertThat(lodgementPosts()).isEqualTo(2);
        // Four separate transactions: a claim and a settle per loan, none rolled back.
        assertThat(transactions.events).containsExactly("begin", "commit", "begin", "commit",
                "begin", "commit", "begin", "commit");
    }

    @Test
    @DisplayName("a loan whose outcome cannot be saved rolls back alone and stays claimed; the next loan commits")
    void aSettleFailureRollsBackOnlyThatLoan() {
        Loan unrecorded = newLoan(41);
        Loan lodged = newLoan(42);
        ndasendaAnswers(reference -> accepted(reference.equals("000000041") ? "BATCH-A" : "BATCH-B"));
        int[] savesOf41 = {0};
        when(loanRepository.save(any(Loan.class))).thenAnswer(call -> {
            Loan loan = call.getArgument(0);
            // The claim's save succeeds; the settle's save of loan 41 does not.
            if (loan.getId() == 41L && ++savesOf41[0] == 2) {
                throw new OptimisticLockingFailureException("loan 41 changed under the settle");
            }
            return loan;
        });

        ndasendaJob().processSsbApprovals();

        assertThat(transactions.events).containsExactly(
                "begin", "commit", "begin", "rollback",  // loan 41: claim committed, settle rolled back
                "begin", "commit", "begin", "commit");   // loan 42: untouched by 41's failure
        assertThat(unrecorded.getLodgementClaimedAt()).as("the committed claim stands").isNotNull();
        assertThat(lodged.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(lodged.getBatchNumber()).isEqualTo("BATCH-B");
        assertThat(lodgementPosts()).isEqualTo(2);
        assertThat(audits()).extracting(AuditLog::getEventType).containsExactly("NDASENDA_LODGEMENT_NOT_RECORDED");
        assertThat(audits().get(0).getDetail()).contains("batch=BATCH-A").doesNotContain(NATIONAL_ID);
    }

    @Test
    @DisplayName("a loan another instance claimed first is not lodged")
    void claimAlreadyTakenIsNotLodged() {
        Loan loan = newLoan(42);
        LocalDateTime theirs = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
        // The due list was read before the other instance's claim committed.
        when(loanRepository.findIdsDueForLodgement(any())).thenReturn(List.of(42L));
        loan.setLodgementClaimedAt(theirs);
        LoanApprovalService service = mock(LoanApprovalService.class);

        job(service).processSsbApprovals();

        verifyNoInteractions(service);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        assertThat(loan.getLodgementClaimedAt()).isEqualTo(theirs);
        verify(loanRepository, never()).save(any());
    }

    @ParameterizedTest(name = "payslip review {0}")
    @EnumSource(value = PayslipReviewStatus.class, names = {"PENDING", "CONFIRMED"})
    @DisplayName("a loan held or rejected at payslip review is never lodged, even when the due list still names it")
    void payslipReviewHoldsTheLoanBack(PayslipReviewStatus review) {
        Loan loan = newLoan(42);
        loan.setPayslipReviewStatus(review);
        // Read before the review landed: the claim re-checks under the lock.
        when(loanRepository.findIdsDueForLodgement(any())).thenReturn(List.of(42L));
        LoanApprovalService service = mock(LoanApprovalService.class);

        job(service).processSsbApprovals();

        verifyNoInteractions(service);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        assertThat(loan.getLodgementClaimedAt()).isNull();
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a loan cleared at payslip review is lodged like any other")
    void clearedLoanIsLodged() {
        Loan loan = newLoan(42);
        loan.setPayslipReviewStatus(PayslipReviewStatus.CLEARED);
        when(loanRepository.findIdsDueForLodgement(any())).thenReturn(List.of(42L));
        LoanApprovalService service = mock(LoanApprovalService.class);
        when(service.requestApproval(any())).thenReturn(
                LoanApprovalResponse.builder().status(LoanApprovalStatus.PROCESSING).batchNumber("BATCH-A").build());

        job(service).processSsbApprovals();

        verify(service).requestApproval(any());
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
    }

    @Test
    @DisplayName("connection refused: nothing was sent, so the loan stays NEW with attempt 1 and a backoff, and the run stops")
    void connectRefusedStaysNewAndStopsTheRun() {
        Loan first = newLoan(41);
        Loan second = newLoan(42);
        ndasendaAnswers(reference -> {
            throw new ResourceAccessException("I/O error on POST request: Connection refused",
                    new ConnectException("Connection refused"));
        });
        NdasendaLodgementJob job = ndasendaJob();
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        job.processSsbApprovals();

        assertThat(first.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        assertThat(first.getApprovalAttempt()).isEqualTo(1);
        assertThat(first.getLodgementClaimedAt()).as("claim released").isNull();
        assertThat(first.getNextLodgementAttemptAt()).isAfterOrEqualTo(before.plusMinutes(10))
                .isBefore(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(11));
        assertThat(first.getLoanStatusMessage()).contains("never reached Ndasenda").contains("Connection refused");
        // Ndasenda is down: the second loan is not even claimed, let alone sent.
        verify(loanRepository, never()).findByIdForUpdate(42L);
        assertThat(second.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        assertThat(second.getApprovalAttempt()).isNull();
        assertThat(lodgementPosts()).isEqualTo(1);
        verifyNoInteractions(auditService, loanNotificationService);

        // And lodging pauses: the next minute's run sends nothing.
        job.processSsbApprovals();
        assertThat(lodgementPosts()).isEqualTo(1);
    }

    @Test
    @DisplayName("the access token cannot be fetched: the lodgement is never sent and the loan stays NEW")
    void tokenFetchFailureIsNotSent() {
        Loan loan = newLoan(42);
        newLoan(43);
        // Even a read timeout on the TOKEN endpoint happens before the lodgement POST exists.
        when(auth.getAccessToken()).thenThrow(new ResourceAccessException("I/O error on POST request for token",
                new SocketTimeoutException("Read timed out")));
        ndasendaAnswers(reference -> accepted("never"));

        ndasendaJob().processSsbApprovals();

        assertThat(lodgementPosts()).isZero();
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        assertThat(loan.getApprovalAttempt()).isEqualTo(1);
        assertThat(loan.getLodgementClaimedAt()).isNull();
        assertThat(loan.getNextLodgementAttemptAt()).isNotNull();
        assertThat(loan.getLoanStatusMessage()).contains("access token could not be fetched");
        verify(loanRepository, never()).findByIdForUpdate(43L);
    }

    @Test
    @DisplayName("the third attempt that never reaches Ndasenda FAILS the loan - nothing lodged, nothing to cancel")
    void thirdNotSentAttemptFails() {
        Loan loan = newLoan(42);
        loan.setApprovalAttempt(2);
        ndasendaAnswers(reference -> {
            throw new ResourceAccessException("I/O error", new ConnectException("Connection refused"));
        });

        ndasendaJob().processSsbApprovals();

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.FAILED);
        assertThat(loan.getApprovalAttempt()).isEqualTo(3);
        assertThat(loan.getLodgementClaimedAt()).isNull();
        assertThat(loan.getNextLodgementAttemptAt()).isNull();
        assertThat(loan.getLoanStatusMessage()).startsWith("Not lodged: 3 attempt(s) never reached Ndasenda");
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        // The application goes no further, so the applicant is told (FR-SSB-016).
        verify(loanNotificationService).notify(loan, LoanNotice.NOT_COMPLETED);
        List<AuditLog> audits = audits();
        assertThat(audits).extracting(AuditLog::getEventType).containsExactly("NDASENDA_LODGEMENT_ATTEMPTS_EXHAUSTED");
        assertThat(audits.get(0).getStateTransitionDelta()).isEqualTo("{\"from\":\"NEW\",\"to\":\"FAILED\"}");
    }

    @Test
    @DisplayName("a loan still in its backoff is not lodged")
    void loanInBackoffIsNotLodged() {
        Loan loan = newLoan(42);
        loan.setApprovalAttempt(1);
        loan.setNextLodgementAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        ndasendaAnswers(reference -> accepted("BATCH"));

        ndasendaJob().processSsbApprovals();

        assertThat(lodgementPosts()).isZero();
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
    }

    static Stream<Arguments> unknownOutcomes() {
        return Stream.of(
                Arguments.of("read timeout", (Function<String, ResponseEntity<NdasendaDeductionBatch>>) ref -> {
                    throw new ResourceAccessException("I/O error on POST request: Read timed out",
                            new SocketTimeoutException("Read timed out"));
                }),
                Arguments.of("connection reset", (Function<String, ResponseEntity<NdasendaDeductionBatch>>) ref -> {
                    throw new ResourceAccessException("I/O error on POST request: Connection reset",
                            new SocketException("Connection reset"));
                }),
                Arguments.of("HTTP 502", (Function<String, ResponseEntity<NdasendaDeductionBatch>>) ref -> {
                    throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY,
                            ("{\"echo\":\"" + NATIONAL_ID + "\"}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                }),
                Arguments.of("HTTP 409", (Function<String, ResponseEntity<NdasendaDeductionBatch>>) ref -> {
                    throw clientError(HttpStatus.CONFLICT);
                }),
                Arguments.of("unreadable 2xx", (Function<String, ResponseEntity<NdasendaDeductionBatch>>) ref -> {
                    throw new UnknownContentTypeException(NdasendaDeductionBatch.class, MediaType.TEXT_HTML,
                            HttpStatus.OK, "OK", HttpHeaders.EMPTY, "<html>login</html>".getBytes(StandardCharsets.UTF_8));
                }),
                Arguments.of("2xx without a batch id", (Function<String, ResponseEntity<NdasendaDeductionBatch>>) ref ->
                        ResponseEntity.ok(NdasendaDeductionBatch.builder().build()))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownOutcomes")
    @DisplayName("an outcome that may have lodged the deduction is held for Ndasenda's answer: audited, not FAILED, not sent again")
    void unknownOutcomeIsHeldNotFailedNotResent(String label,
                                               Function<String, ResponseEntity<NdasendaDeductionBatch>> answer) {
        Loan loan = newLoan(42);
        Loan next = newLoan(43);
        ndasendaAnswers(answer);

        ndasendaJob().processSsbApprovals();

        // PROCESSING is what the response job resolves with Ndasenda's own answer.
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getBatchNumber()).isNull();
        assertThat(loan.getApprovalAttempt()).isNull();
        assertThat(loan.getLodgementClaimedAt()).as("the claim stays: never sent again").isNotNull();
        assertThat(loan.getLoanStatusMessage()).startsWith("Ndasenda lodgement outcome unknown")
                .doesNotContain(NATIONAL_ID);
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        assertThat(lodgementPosts()).as("sent exactly once").isEqualTo(1);
        verifyNoInteractions(loanNotificationService);

        List<AuditLog> audits = audits();
        assertThat(audits).extracting(AuditLog::getEventType).containsExactly("NDASENDA_LODGEMENT_UNKNOWN");
        assertThat(audits.get(0).getStateTransitionDelta()).isEqualTo("{\"from\":\"NEW\",\"to\":\"PROCESSING\"}");
        assertThat(audits.get(0).getDetail()).contains("reference=000000042").contains("ecNumber=*****67A")
                .doesNotContain(NATIONAL_ID).doesNotContain(EC_NUMBER);
        assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().startsWith("NDASENDA LODGEMENT OUTCOME UNKNOWN"));

        // Ndasenda may be unwell: the run stopped before the next loan.
        verify(loanRepository, never()).findByIdForUpdate(43L);
        assertThat(next.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);

        // A fresh job (a restart, another instance: no pause) still never sends the held loan again.
        ndasendaJob().processSsbApprovals();
        assertThat(mockingDetails(restTemplate).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("exchange"))
                .map(call -> ((NdasendaDeductionBatch) ((HttpEntity<?>) call.getArgument(2)).getBody())
                        .getDeductions().get(0).getReference()))
                .containsOnly("000000042", "000000043")
                .filteredOn("000000042"::equals).hasSize(1);
    }

    @Test
    @DisplayName("an unreadable answer is no longer re-POSTed: the token is refreshed, the lodgement is not")
    void unreadableAnswerRefreshesTokenButDoesNotResend() {
        newLoan(42);
        ndasendaAnswers(reference -> {
            throw new UnknownContentTypeException(NdasendaDeductionBatch.class, MediaType.TEXT_HTML,
                    HttpStatus.OK, "OK", HttpHeaders.EMPTY, new byte[0]);
        });

        ndasendaJob().processSsbApprovals();

        assertThat(lodgementPosts()).isEqualTo(1);
        verify(auth).refreshToken();
    }

    @Test
    @DisplayName("Ndasenda's own refusal (4xx) FAILS the loan, audited, with no upstream body copied")
    void refusalFails() {
        Loan loan = newLoan(42);
        ndasendaAnswers(reference -> {
            throw clientError(HttpStatus.UNPROCESSABLE_ENTITY);
        });

        ndasendaJob().processSsbApprovals();

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.FAILED);
        assertThat(loan.getApprovalAttempt()).isEqualTo(1);
        assertThat(loan.getLoanStatusMessage()).isEqualTo("Ndasenda refused the lodgement with HTTP 422");
        assertThat(loan.getDeductionCancellationStatus()).isNull();
        assertThat(lodgementPosts()).isEqualTo(1);
        verify(loanNotificationService).notify(loan, LoanNotice.NOT_COMPLETED);
        assertThat(audits()).extracting(AuditLog::getEventType).containsExactly("NDASENDA_LODGEMENT_REFUSED");
    }

    @Test
    @DisplayName("a 401 is replayed once with a fresh token (Ndasenda processed nothing), and that replay can succeed")
    void unauthorizedIsReplayedOnce() {
        Loan loan = newLoan(42);
        int[] posts = {0};
        ndasendaAnswers(reference -> {
            if (++posts[0] == 1) {
                throw clientError(HttpStatus.UNAUTHORIZED);
            }
            return accepted("BATCH-AFTER-REFRESH");
        });

        ndasendaJob().processSsbApprovals();

        assertThat(lodgementPosts()).isEqualTo(2);
        verify(auth).refreshToken();
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getBatchNumber()).isEqualTo("BATCH-AFTER-REFRESH");
    }

    @Test
    @DisplayName("a 401 on the replay too means nothing was processed: NEW with attempt 1, run stopped, no third POST")
    void unauthorizedTwiceIsNotSent() {
        Loan loan = newLoan(42);
        newLoan(43);
        ndasendaAnswers(reference -> {
            throw clientError(HttpStatus.UNAUTHORIZED);
        });

        ndasendaJob().processSsbApprovals();

        assertThat(lodgementPosts()).isEqualTo(2);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        assertThat(loan.getApprovalAttempt()).isEqualTo(1);
        assertThat(loan.getLodgementClaimedAt()).isNull();
        verify(loanRepository, never()).findByIdForUpdate(43L);
    }

    @Test
    @DisplayName("a deduction that cannot be built (an instalment too large for the wire) is not sent, and the run carries on")
    void unbuildableDeductionIsNotSentAndRunCarriesOn() {
        Loan oversized = newLoan(41);
        oversized.setGrossedMonthlyDeduction(new BigDecimal("21474836.48"));
        Loan lodged = newLoan(42);
        ndasendaAnswers(reference -> accepted("BATCH-42"));

        ndasendaJob().processSsbApprovals();

        assertThat(oversized.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        assertThat(oversized.getApprovalAttempt()).isEqualTo(1);
        assertThat(oversized.getLoanStatusMessage()).contains("could not be built");
        assertThat(lodged.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(lodgementPosts()).isEqualTo(1);
    }

    @Test
    @DisplayName("an exception nobody classified is treated as an unknown outcome, never as FAILED")
    void unclassifiedFailureIsHeld() {
        Loan loan = newLoan(42);
        LoanApprovalService service = mock(LoanApprovalService.class);
        when(service.requestApproval(any())).thenThrow(new IllegalStateException("boom after send"));

        job(service).processSsbApprovals();

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getLodgementClaimedAt()).isNotNull();
        verify(service, times(1)).requestApproval(any());
    }

    @Test
    @DisplayName("a claim a dead run left behind is held for Ndasenda's answer, not sent again")
    void abandonedClaimIsHeld() {
        Loan loan = newLoan(42);
        loan.setLodgementClaimedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(45));
        LoanApprovalService service = mock(LoanApprovalService.class);

        job(service).processSsbApprovals();

        verifyNoInteractions(service);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getLodgementClaimedAt()).isNotNull();
        assertThat(loan.getLoanStatusMessage()).contains("was never recorded");
        assertThat(audits()).extracting(AuditLog::getEventType).containsExactly("NDASENDA_LODGEMENT_UNKNOWN");
    }

    @Test
    @DisplayName("a recent claim is an in-flight lodgement: left alone")
    void recentClaimIsLeftAlone() {
        Loan loan = newLoan(42);
        LocalDateTime claimed = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        loan.setLodgementClaimedAt(claimed);
        LoanApprovalService service = mock(LoanApprovalService.class);

        job(service).processSsbApprovals();

        verifyNoInteractions(service, auditService);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        assertThat(loan.getLodgementClaimedAt()).isEqualTo(claimed);
    }

    @Test
    @DisplayName("a slow lodgement held as abandoned meanwhile still records Ndasenda's batch id when it returns")
    void lateAcceptanceAfterHoldIsRecorded() {
        Loan loan = newLoan(42);
        LoanApprovalService service = mock(LoanApprovalService.class);
        when(service.requestApproval(any())).thenAnswer(call -> {
            // Another instance's sweep held the loan while this call hung.
            loan.setLoanApprovalStatus(LoanApprovalStatus.PROCESSING);
            return LoanApprovalResponse.builder().status(LoanApprovalStatus.PROCESSING).batchNumber("BATCH-LATE").build();
        });

        job(service).processSsbApprovals();

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        assertThat(loan.getBatchNumber()).isEqualTo("BATCH-LATE");
    }

    @Test
    @DisplayName("an outcome for a loan that moved on meanwhile (Ndasenda already answered) is audited, not applied")
    void outcomeForLoanThatMovedOnIsNotApplied() {
        Loan loan = newLoan(42);
        LoanApprovalService service = mock(LoanApprovalService.class);
        when(service.requestApproval(any())).thenAnswer(call -> {
            loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
            loan.setApprovalReference("ND-7001");
            throw new LodgementException(LodgementException.Kind.REFUSED, "Ndasenda refused the lodgement with HTTP 400", null);
        });

        job(service).processSsbApprovals();

        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(audits()).extracting(AuditLog::getEventType).containsExactly("NDASENDA_LODGEMENT_NOT_RECORDED");
    }

    @Test
    @DisplayName("the lodgement is logged by reference and masked EC number, never the national ID or full EC number")
    void lodgementLogsNoPii() {
        newLoan(42);
        ndasendaAnswers(reference -> accepted("BATCH-20260929-01"));

        ndasendaJob().processSsbApprovals();

        assertThat(logs.list).isNotEmpty()
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .doesNotContain(NATIONAL_ID).doesNotContain(EC_NUMBER));
        assertThat(logs.list).anyMatch(event -> event.getFormattedMessage()
                .equals("Lodging Ndasenda deduction for reference 000000042 ec *****67A tenor 6"));
    }

    /** Opens, commits and rolls back nothing real; records the order so each loan's boundaries are visible. */
    private static final class RecordingTransactions implements PlatformTransactionManager {
        final List<String> events = new ArrayList<>();
        final List<Integer> propagations = new ArrayList<>();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            events.add("begin");
            propagations.add(definition.getPropagationBehavior());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            events.add("commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback");
        }
    }
}
