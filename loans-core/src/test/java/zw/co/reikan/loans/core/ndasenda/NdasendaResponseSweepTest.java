package zw.co.reikan.loans.core.ndasenda;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanBatchService;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.loan.NdasendaAwaitingLoan;
import zw.co.reikan.loans.core.ndasenda.jobs.NdasendaDeductionBatchResponsesDailyJob;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The response job used to read today's responses only, so a day it missed left the loan PROCESSING
 * for good; a fetch error read as "no responses"; and nothing noticed a loan left waiting. Each run now
 * reads back to the oldest lodgement still waiting, reports fetch failures, and reports an overdue
 * lodgement once.
 */
class NdasendaResponseSweepTest {

    private static final String BY_DATE = "responses-by-date";
    private static final String BY_BATCH = "responses-by-batch";
    private static final String DEDUCTION_CODE = "DED-7781";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private static final LocalDateTime NOW = TODAY.atTime(10, 0);
    private static final String EC_NUMBER = "1234567A";
    private static final String NATIONAL_ID = "63-1234567A63";

    private RestTemplate restTemplate;
    private LoanRepository loanRepository;
    private NotificationService notificationService;
    private AuditService auditService;
    private NdasendaParameters props;
    private NdasendaLoanApprovalServiceImpl service;

    /** What the fake Ndasenda answers: batches listed per "from/to", records per batch, failures per key. */
    private final Map<String, List<String>> listings = new HashMap<>();
    private final Map<String, List<NdasendaDeduction>> records = new HashMap<>();
    private final Map<String, RuntimeException> failures = new HashMap<>();
    private final List<String> listingsRequested = new ArrayList<>();

    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        loanRepository = mock(LoanRepository.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        props = new NdasendaParameters();
        props.setDeductionResponsesByDateRangeEndpoint(BY_DATE);
        props.setDeductionResponsesByBatchId(BY_BATCH);
        props.setDeductionCode(DEDUCTION_CODE);
        service = new NdasendaLoanApprovalServiceImpl(restTemplate, mock(NdasendaAuthServiceImpl.class), props,
                loanRepository, mock(LoanBatchService.class), notificationService, auditService,
                new DeductionCancellationService(loanRepository, auditService, mock(AuthService.class)));

        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class), any(Object[].class)))
                .thenAnswer(call -> {
                    if (BY_DATE.equals(call.getArgument(0))) {
                        String key = call.getArgument(4) + "/" + call.getArgument(5);
                        listingsRequested.add(key);
                        if (failures.containsKey(key)) {
                            throw failures.get(key);
                        }
                        return ResponseEntity.ok(listings.getOrDefault(key, List.of()).stream()
                                .map(id -> NdasendaDeductionsBatchRequest.builder().id(id).build())
                                .toList());
                    }
                    String batchId = call.getArgument(4);
                    if (failures.containsKey(batchId)) {
                        throw failures.get(batchId);
                    }
                    return ResponseEntity.ok(List.of(NdasendaDeductionsBatchRequest.builder()
                            .id(batchId).deductions(records.getOrDefault(batchId, List.of())).build()));
                });

        serviceLogger = (Logger) LoggerFactory.getLogger(NdasendaLoanApprovalServiceImpl.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        serviceLogger.detachAppender(logs);
    }

    private static String day(LocalDate date) {
        return String.format("%04d%02d%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    private static NdasendaAwaitingLoan awaiting(long id, LocalDateTime dateApproved, LocalDateTime createdDate) {
        return new NdasendaAwaitingLoan(id, LoanApprovalStatus.PROCESSING, "BATCH-" + id, EC_NUMBER,
                dateApproved, createdDate, null);
    }

    private Loan processingLoan(long id, LocalDateTime lodgedAt) {
        Loan loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.PROCESSING)
                .batchNumber("BATCH-" + id)
                .dateApproved(lodgedAt)
                .ecNumber(EC_NUMBER)
                .nationalIdNumber(NATIONAL_ID)
                .mobileNumber("0772123123")
                .disbursedAmount(new BigDecimal("500.00"))
                .build();
        loan.setId(id);
        when(loanRepository.findById(id)).thenReturn(Optional.of(loan));
        return loan;
    }

    private static NdasendaDeduction answer(String id, long loanId, NdasendaDeductionStatus status) {
        return NdasendaDeduction.builder().id(id).reference(String.format("%09d", loanId))
                .type(NdasendaDeductionType.NEW).status(status).ecNumber(EC_NUMBER).idNumber(NATIONAL_ID).build();
    }

    private List<ILoggingEvent> errors() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    // --- window ------------------------------------------------------------------------------

    @Test
    @DisplayName("window: nothing awaiting reads today only")
    void nothingAwaitingReadsToday() {
        assertThat(NdasendaLoanApprovalServiceImpl.responseWindowStart(TODAY, List.of(), 45)).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("window: starts the day before the OLDEST awaiting lodgement")
    void oldestAwaitingLodgementLessADay() {
        List<NdasendaAwaitingLoan> waiting = List.of(
                awaiting(1, LocalDateTime.of(2026, 9, 27, 9, 0), LocalDateTime.of(2026, 9, 27, 8, 0)),
                awaiting(2, LocalDateTime.of(2026, 9, 20, 23, 50), LocalDateTime.of(2026, 9, 18, 8, 0)),
                awaiting(3, LocalDateTime.of(2026, 9, 25, 7, 0), LocalDateTime.of(2026, 9, 25, 6, 0)));

        assertThat(NdasendaLoanApprovalServiceImpl.responseWindowStart(TODAY, waiting, 45))
                .isEqualTo(LocalDate.of(2026, 9, 19));
    }

    @Test
    @DisplayName("window: a loan with no lodgement stamp is dated by its application, which is never later")
    void createdDateStandsInForAMissingLodgementStamp() {
        List<NdasendaAwaitingLoan> waiting = List.of(awaiting(1, null, LocalDateTime.of(2026, 9, 22, 8, 0)));

        assertThat(NdasendaLoanApprovalServiceImpl.responseWindowStart(TODAY, waiting, 45))
                .isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    @DisplayName("window: never further back than the lookback cap")
    void cappedAtLookback() {
        List<NdasendaAwaitingLoan> waiting = List.of(
                awaiting(1, LocalDateTime.of(2026, 6, 1, 9, 0), LocalDateTime.of(2026, 6, 1, 8, 0)));

        assertThat(NdasendaLoanApprovalServiceImpl.responseWindowStart(TODAY, waiting, 45))
                .isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(NdasendaLoanApprovalServiceImpl.responseWindowStart(TODAY, waiting, 0)).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("window: a lodgement stamped today still reads yesterday, and a future stamp never passes today")
    void marginAndFutureStamp() {
        assertThat(NdasendaLoanApprovalServiceImpl.responseWindowStart(TODAY,
                List.of(awaiting(1, NOW.minusHours(1), NOW.minusHours(2))), 45)).isEqualTo(TODAY.minusDays(1));
        assertThat(NdasendaLoanApprovalServiceImpl.responseWindowStart(TODAY,
                List.of(awaiting(1, NOW.plusDays(3), NOW.plusDays(3))), 45)).isEqualTo(TODAY);
    }

    // --- the sweep ---------------------------------------------------------------------------

    @Test
    @DisplayName("a missed day is read: a loan lodged on the 20th takes a SUCCESS dated the 22nd")
    void missedDayIsReadAndApplied() {
        Loan loan = processingLoan(42, LocalDateTime.of(2026, 9, 20, 11, 0));
        when(loanRepository.findAwaitingNdasendaOutcome()).thenReturn(List.of(
                awaiting(42, loan.getDateApproved(), loan.getDateApproved().minusHours(1))));
        listings.put(day(LocalDate.of(2026, 9, 19)) + "/" + day(TODAY.minusDays(1)), List.of("B-0922"));
        records.put("B-0922", List.of(answer("ND-1", 42, NdasendaDeductionStatus.SUCCESS)));

        ResponseSweepResult result = service.sweepDeductionResponses(NOW);

        assertThat(listingsRequested).containsExactly("20260919/20260928", "20260929/20260929");
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(loan.getApprovalReference()).isEqualTo("ND-1");
        verify(loanRepository).save(loan);
        assertThat(result.from()).isEqualTo(LocalDate.of(2026, 9, 19));
        assertThat(result.to()).isEqualTo(TODAY);
        assertThat(result.awaitingLoans()).isEqualTo(1);
        assertThat(result.batchesRead()).isEqualTo(1);
        assertThat(result.recordsProcessed()).isEqualTo(1);
        assertThat(result.incomplete()).isFalse();
    }

    @Test
    @DisplayName("earlier days are taken only for loans still waiting; today's are taken in full, as before")
    void earlierDaysOnlyForWaitingLoans() {
        processingLoan(42, LocalDateTime.of(2026, 9, 25, 11, 0));
        when(loanRepository.findAwaitingNdasendaOutcome()).thenReturn(List.of(
                awaiting(42, LocalDateTime.of(2026, 9, 25, 11, 0), LocalDateTime.of(2026, 9, 25, 10, 0))));
        // Loan 7 was decided long ago: its old record and an unreadable reference are not re-read every run.
        listings.put("20260924/20260928", List.of("B-OLD"));
        records.put("B-OLD", List.of(answer("ND-2", 7, NdasendaDeductionStatus.FAILED),
                NdasendaDeduction.builder().id("ND-3").reference("LN-ABC").status(NdasendaDeductionStatus.SUCCESS).build()));
        // Today's unknown reference is still reported, as it always was.
        when(loanRepository.findById(99L)).thenReturn(Optional.empty());
        listings.put("20260929/20260929", List.of("B-NEW"));
        records.put("B-NEW", List.of(answer("ND-4", 99, NdasendaDeductionStatus.SUCCESS)));

        ResponseSweepResult result = service.sweepDeductionResponses(NOW);

        verify(loanRepository, never()).findById(7L);
        verify(loanRepository).findById(99L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        assertThat(captor.getValue().build().getEventType()).isEqualTo("NDASENDA_RESPONSE_UNMATCHED");
        assertThat(captor.getValue().build().getEntityId()).isEqualTo("ND-4");
        assertThat(result.batchesRead()).isEqualTo(2);
        assertThat(result.recordsProcessed()).isEqualTo(1);
    }

    // --- fetch failures ----------------------------------------------------------------------

    @Test
    @DisplayName("a listing that cannot be fetched is an ERROR naming the range and cause, is in the result, and never throws")
    void listingFailureIsReportedNotSwallowed() {
        Loan loan = processingLoan(42, LocalDateTime.of(2026, 9, 25, 11, 0));
        when(loanRepository.findAwaitingNdasendaOutcome()).thenReturn(List.of(
                awaiting(42, loan.getDateApproved(), loan.getDateApproved())));
        failures.put("20260924/20260928", new ResourceAccessException("I/O error on GET request for "
                + "\"https://ndasenda.example/api/v1/deductions/responses/20260924/20260928/" + DEDUCTION_CODE
                + "\": Connection refused", new ConnectException("Connection refused")));
        listings.put("20260929/20260929", List.of("B-NEW"));
        records.put("B-NEW", List.of(answer("ND-5", 42, NdasendaDeductionStatus.FAILED)));

        ResponseSweepResult[] result = new ResponseSweepResult[1];
        assertThatCode(() -> result[0] = service.sweepDeductionResponses(NOW)).doesNotThrowAnyException();

        assertThat(result[0].incomplete()).isTrue();
        assertThat(result[0].fetchFailures()).containsExactly(
                "batches 2026-09-24..2026-09-28: ResourceAccessException caused by ConnectException");
        // Today's batch was still read, and applied.
        assertThat(result[0].batchesRead()).isEqualTo(1);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);

        assertThat(errors()).hasSize(1);
        String line = errors().get(0).getFormattedMessage();
        assertThat(line).startsWith("NDASENDA RESPONSE FETCH FAILED")
                .contains("2026-09-24", "2026-09-28", "ResourceAccessException caused by ConnectException")
                .doesNotContain(DEDUCTION_CODE, "https://");
        assertThat(errors().get(0).getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("one batch that cannot be read does not stop the others, and is reported with its HTTP status")
    void batchFailureDoesNotStopTheRest() {
        Loan loan = processingLoan(42, NOW.minusHours(3));
        listings.put("20260929/20260929", List.of("B-BAD", "B-GOOD"));
        failures.put("B-BAD", HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                HttpHeaders.EMPTY, ("{\"records\":[{\"idNumber\":\"" + NATIONAL_ID + "\"}]}").getBytes(), null));
        records.put("B-GOOD", List.of(answer("ND-6", 42, NdasendaDeductionStatus.SUCCESS)));

        ResponseSweepResult result = service.sweepDeductionResponses(NOW);

        assertThat(result.fetchFailures()).containsExactly("batch B-BAD: ServiceUnavailable HTTP 503");
        assertThat(result.batchesRead()).isEqualTo(1);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(errors()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("batch B-BAD", "2026-09-29", "ServiceUnavailable HTTP 503")
                .doesNotContain(NATIONAL_ID));
    }

    @Test
    @DisplayName("the job reports an incomplete run at ERROR and never lets the run's failure out")
    void jobReportsAndContains() {
        Logger jobLogger = (Logger) LoggerFactory.getLogger(NdasendaDeductionBatchResponsesDailyJob.class);
        ListAppender<ILoggingEvent> jobLogs = new ListAppender<>();
        jobLogs.start();
        jobLogger.addAppender(jobLogs);
        try {
            NdasendaLoanApprovalServiceImpl approvals = mock(NdasendaLoanApprovalServiceImpl.class);
            NdasendaDeductionBatchResponsesDailyJob job = new NdasendaDeductionBatchResponsesDailyJob(approvals);

            when(approvals.sweepDeductionResponses(any())).thenReturn(new ResponseSweepResult(TODAY.minusDays(3),
                    TODAY, 2, 4, 9, List.of("batch B-BAD: ServiceUnavailable HTTP 503"), 0));
            job.execute();
            assertThat(jobLogs.list).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getFormattedMessage()).contains("INCOMPLETE", "2026-09-26", "2026-09-29",
                        "batch B-BAD: ServiceUnavailable HTTP 503");
            });

            when(approvals.sweepDeductionResponses(any())).thenThrow(new IllegalStateException("pool exhausted"));
            assertThatCode(job::execute).doesNotThrowAnyException();
            assertThat(jobLogs.list).hasSize(2);
            assertThat(jobLogs.list.get(1).getLevel()).isEqualTo(Level.ERROR);
        } finally {
            jobLogger.detachAppender(jobLogs);
        }
    }

    // --- overdue -----------------------------------------------------------------------------

    @Test
    @DisplayName("a lodgement waiting past the threshold is marked, logged and audited once - never again")
    void overdueIsAuditedOnce() {
        LocalDateTime lodged = NOW.minusDays(8);
        Loan loan = processingLoan(42, lodged);
        when(loanRepository.findAwaitingNdasendaOutcome()).thenReturn(List.of(awaiting(42, lodged, lodged)));

        ResponseSweepResult first = service.sweepDeductionResponses(NOW);

        assertThat(first.newlyOverdue()).isEqualTo(1);
        assertThat(loan.getNdasendaResponseOverdueAt()).isEqualTo(NOW);
        assertThat(loan.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.PROCESSING);
        verify(loanRepository).save(loan);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        AuditLog audit = captor.getValue().build();
        assertThat(audit.getEventType()).isEqualTo("NDASENDA_RESPONSE_OVERDUE");
        assertThat(audit.getEntityType()).isEqualTo("LOAN");
        assertThat(audit.getEntityId()).isEqualTo("42");
        assertThat(audit.getActorId()).isEqualTo("ndasenda-response-job");
        assertThat(audit.getCorrelationId()).isEqualTo("BATCH-42");
        assertThat(audit.getDetail())
                .contains("loanStatus=PROCESSING", "waitedDays=8", "thresholdDays=7", "reference=000000042",
                        "ecNumber=*****67A")
                .doesNotContain(EC_NUMBER, NATIONAL_ID);
        assertThat(errors()).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .startsWith("NDASENDA RESPONSE OVERDUE: loan 42")
                .contains("*****67A")
                .doesNotContain(EC_NUMBER, NATIONAL_ID));
        verifyNoInteractions(notificationService);

        // Next run: the list now carries the mark, and the loaded loan has it too.
        when(loanRepository.findAwaitingNdasendaOutcome()).thenReturn(List.of(
                new NdasendaAwaitingLoan(42L, LoanApprovalStatus.PROCESSING, "BATCH-42", EC_NUMBER, lodged, lodged, NOW)));
        ResponseSweepResult second = service.sweepDeductionResponses(NOW.plusMinutes(10));

        assertThat(second.newlyOverdue()).isZero();
        verify(auditService, times(1)).record(any());
        verify(loanRepository, times(1)).save(any());
        assertThat(loan.getNdasendaResponseOverdueAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a mark another run already saved is not reported again, even when the list was read before it")
    void markOnTheLoanItselfWins() {
        LocalDateTime lodged = NOW.minusDays(9);
        Loan loan = processingLoan(42, lodged);
        loan.setNdasendaResponseOverdueAt(NOW.minusDays(2));
        when(loanRepository.findAwaitingNdasendaOutcome()).thenReturn(List.of(awaiting(42, lodged, lodged)));

        assertThat(service.sweepDeductionResponses(NOW).newlyOverdue()).isZero();

        verify(loanRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("within the threshold nothing is reported, and a loan this run answered is not reported overdue")
    void notYetOverdueOrJustAnswered() {
        LocalDateTime recent = NOW.minusDays(6);
        processingLoan(41, recent);
        LocalDateTime old = NOW.minusDays(10);
        Loan answered = processingLoan(42, old);
        when(loanRepository.findAwaitingNdasendaOutcome()).thenReturn(List.of(
                awaiting(41, recent, recent), awaiting(42, old, old)));
        listings.put("20260918/20260928", List.of("B-0925"));
        records.put("B-0925", List.of(answer("ND-7", 42, NdasendaDeductionStatus.SUCCESS)));

        ResponseSweepResult result = service.sweepDeductionResponses(NOW);

        assertThat(result.newlyOverdue()).isZero();
        assertThat(answered.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.APPROVED);
        assertThat(answered.getNdasendaResponseOverdueAt()).isNull();
        verify(loanRepository, never()).findById(41L);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("an overdue report that fails for one loan does not stop the others")
    void overdueFailureIsContained() {
        LocalDateTime lodged = NOW.minusDays(8);
        when(loanRepository.findById(41L)).thenThrow(new IllegalStateException("connection reset"));
        Loan other = processingLoan(42, lodged);
        when(loanRepository.findAwaitingNdasendaOutcome()).thenReturn(List.of(
                awaiting(41, lodged, lodged), awaiting(42, lodged, lodged)));

        ResponseSweepResult result = service.sweepDeductionResponses(NOW);

        assertThat(result.newlyOverdue()).isEqualTo(1);
        assertThat(other.getNdasendaResponseOverdueAt()).isEqualTo(NOW);
        assertThat(errors()).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(line -> assertThat(line).startsWith("Could not report loan 41 overdue"))
                .anySatisfy(line -> assertThat(line).startsWith("NDASENDA RESPONSE OVERDUE: loan 42"));
    }
}
