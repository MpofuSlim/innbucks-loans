package zw.co.innbucks.loans.core.ndasenda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.UnknownContentTypeException;
import zw.co.innbucks.loans.core.TextUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.DeductionCancellationStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.NdasendaAwaitingLoan;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

@Slf4j
@RequiredArgsConstructor
@Service
public class NdasendaLoanApprovalServiceImpl implements LoanApprovalService {

    private static final BigDecimal CENTS = new BigDecimal("100");
    static final String RESPONSE_UNMATCHED = "NDASENDA_RESPONSE_UNMATCHED";
    static final String RESPONSE_FAILED = "NDASENDA_RESPONSE_FAILED";
    static final String RESPONSE_CONFLICT = "NDASENDA_RESPONSE_CONFLICT";
    static final String RESPONSE_IGNORED = "NDASENDA_RESPONSE_IGNORED";
    static final String RESPONSE_MISMATCH = "NDASENDA_RESPONSE_MISMATCH";
    static final String RESPONSE_OVERDUE = "NDASENDA_RESPONSE_OVERDUE";
    private static final String DEDUCTION_ENTITY = "NDASENDA_DEDUCTION";
    static final String SYSTEM_ACTOR = "ndasenda-response-job";
    private static final DateTimeFormatter DEDUCTION_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final RestTemplate restTemplate;
    private final NdasendaAuthService ndasendaAuthService;
    private final NdasendaParameters ndasendaProps;
    private final LoanRepository loanRepository;
    private final LoanBatchService loanBatchService;
    private final LoanNotificationService loanNotificationService;
    private final AuditService auditService;
    private final DeductionCancellationService deductionCancellationService;
    private final MarketTimeZone marketTimeZone;

    /** What the applicant is told of SSB's answer (FR-SSB-016). */
    static final Map<LoanApprovalStatus, LoanNotice> NOTICES = Map.of(
            LoanApprovalStatus.APPROVED, LoanNotice.SSB_CONFIRMED,
            LoanApprovalStatus.REJECTED, LoanNotice.DECLINED);

    /**
     * Lodges ONE deduction with Ndasenda: a payroll stop order on a civil servant's salary, which
     * cannot be taken back once it lands. Returns only when Ndasenda accepted it and named its batch;
     * every other outcome is a {@link LodgementException} saying whether the deduction may have been
     * lodged. The POST is sent once, and again only after a 401 (Ndasenda refused our token and
     * processed nothing).
     */
    public LoanApprovalResponse requestApproval(LoanApprovalRequest request) {
        // Identifiers only: the request carries the national ID and the full EC number.
        log.info("Lodging Ndasenda deduction for reference {} ec {} tenor {}",
                request.getReference(), maskEcNumber(request.getEcnumber()), request.getTenor());

        final LocalDate loanStartDate;
        final LocalDate loanEndDate;
        final NdasendaDeductionBatch batch;
        try {
            // The month is the market's: a loan lodged at 00:30 on the 1st in Harare is still 22:30 on the
            // last day of the previous month in UTC, and would have been deducted a month early.
            loanStartDate = marketTimeZone.today().plusMonths(1).withDayOfMonth(1);
            LocalDate endDate = loanStartDate.plusMonths(request.getTenor() - 1); //subtract 1 because month is inclusive
            loanEndDate = endDate.withDayOfMonth(endDate.lengthOfMonth());

            final NdasendaDeduction deductionRequest = fromLoanRequest(request, loanStartDate, loanEndDate);
            final List<NdasendaDeduction> deductions = List.of(deductionRequest);
            batch = NdasendaDeductionBatch.builder()
                    .totalAmountInCents(deductionRequest.getAmountInCents())
                    .recordsCount(deductions.size())
                    .deductionCode(ndasendaProps.getDeductionCode())
                    .securityToken(ndasendaProps.getSecurityCode())
                    .deductions(deductions)
                    .build();
        } catch (RuntimeException ex) {
            // Our own code, before anything was sent (an instalment too large for the wire, say).
            throw new LodgementException(LodgementException.Kind.NOT_SENT,
                    "The deduction could not be built: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }

        ResponseEntity<NdasendaDeductionBatch> response = lodge(batch);
        NdasendaDeductionBatch accepted = response == null ? null : response.getBody();
        if (accepted == null || StringUtils.isBlank(accepted.getId())) {
            // Not an error status, so it may well have been accepted: only Ndasenda's answer can tell.
            throw new LodgementException(LodgementException.Kind.OUTCOME_UNKNOWN, "Ndasenda answered HTTP "
                    + (response == null ? "(none)" : response.getStatusCode().value()) + " without a batch id", null);
        }

        return LoanApprovalResponse.builder()
                .status(LoanApprovalStatus.PROCESSING)
                .batchNumber(accepted.getId())
                .startDate(loanStartDate)
                .endDate(loanEndDate)
                .build();
    }

    /**
     * The lodgement POST. Unlike {@link #executeWithTokenRefreshRetry}, it is sent again ONLY after a
     * 401: an unreadable 2xx/3xx body is no proof Ndasenda processed nothing, so it is an unknown
     * outcome, not a reason to resend.
     */
    private ResponseEntity<NdasendaDeductionBatch> lodge(NdasendaDeductionBatch batch) {
        try {
            return postLodgement(batch);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
                throw LodgementException.classify(ex);
            }
            log.warn("Ndasenda refused our access token for a lodgement, so processed nothing;"
                    + " refreshing the token and sending it once more");
            try {
                ndasendaAuthService.refreshToken();
            } catch (RuntimeException refreshFailed) {
                throw new LodgementException(LodgementException.Kind.NDASENDA_UNAVAILABLE,
                        "Ndasenda answered HTTP 401 and a new access token could not be fetched: "
                                + LodgementException.describe(refreshFailed), refreshFailed);
            }
            try {
                return postLodgement(batch);
            } catch (RuntimeException retryFailed) {
                throw LodgementException.classify(retryFailed);
            }
        } catch (UnknownContentTypeException ex) {
            // This used to refresh the token and POST again: a blind resend of a lodgement that may have
            // been accepted. The token is still refreshed, in case a stale one is why the body is
            // unreadable, so the next lodgement is not met the same way.
            refreshTokenQuietly();
            throw LodgementException.classify(ex);
        } catch (RuntimeException ex) {
            throw LodgementException.classify(ex);
        }
    }

    /** The token is fetched before anything is sent, so failing to get one is provably not a lodgement. */
    private ResponseEntity<NdasendaDeductionBatch> postLodgement(NdasendaDeductionBatch batch) {
        if (StringUtils.isBlank(ndasendaProps.getDeductionRequestsEndpoint())) {
            // Otherwise the client's own refusal of a missing URL would read as an unknown outcome.
            throw new LodgementException(LodgementException.Kind.NDASENDA_UNAVAILABLE,
                    "No Ndasenda lodgement endpoint is configured", null);
        }
        final HttpHeaders headers;
        try {
            headers = getHttpHeaders();
        } catch (RuntimeException ex) {
            throw new LodgementException(LodgementException.Kind.NDASENDA_UNAVAILABLE,
                    "The Ndasenda access token could not be fetched: " + LodgementException.describe(ex), ex);
        }
        return restTemplate.exchange(ndasendaProps.getDeductionRequestsEndpoint(), POST,
                new HttpEntity<>(batch, headers), NdasendaDeductionBatch.class);
    }

    private void refreshTokenQuietly() {
        try {
            ndasendaAuthService.refreshToken();
        } catch (RuntimeException ex) {
            log.warn("Could not refresh the Ndasenda access token: {}", LodgementException.describe(ex));
        }
    }

    /**
     * One run of the response job. Ndasenda's answer can land on any day after the lodgement, and the
     * job used to read today only, so a day it never read (the job was down, Ndasenda was late, a
     * fetch failed) left the loan PROCESSING for good - where it also blocks the customer from
     * applying again. Each run now reads back to the oldest lodgement still waiting, a day early,
     * capped at {@code ndasenda.responses.lookback-days}, and then reports lodgements that have waited
     * longer than {@code ndasenda.responses.overdue-after-days}, once each.
     *
     * <p>Today's responses are processed in full, as the job always has. Earlier days are processed
     * only for loans still waiting: the rest of them was settled, or reported, when it was first read,
     * and taking it again on every run for up to the whole lookback would re-report every settled
     * conflict and reload each of those loans every 10 minutes. The earlier days are read before today,
     * so a loan with a record on each takes the earlier day's, as it would have had the job read that day.</p>
     *
     * <p>A listing or batch that cannot be fetched is logged at ERROR and returned in the result, never
     * thrown: the rest of the run carries on, and the next run reads it again.</p>
     */
    public ResponseSweepResult sweepDeductionResponses(LocalDateTime now) {
        // now is UTC, like every stored stamp it is compared with; the day is the market's, which is how
        // Ndasenda dates its batches.
        LocalDate today = marketTimeZone.localDay(now);
        List<NdasendaAwaitingLoan> awaiting = loanRepository.findAwaitingNdasendaOutcome();
        LocalDate from = responseWindowStart(today, awaiting, ndasendaProps.getResponses().getLookbackDays());
        log.info("Process deduction responses from {} to {}: {} loan(s) awaiting Ndasenda", from, today, awaiting.size());

        SweepTally tally = new SweepTally();
        if (from.isBefore(today)) {
            Set<Long> awaitingIds = awaiting.stream().map(NdasendaAwaitingLoan::id).collect(Collectors.toSet());
            readResponses(from, today.minusDays(1),
                    deduction -> awaitingIds.contains(loanIdOrNull(deduction.getReference())), tally);
        }
        readResponses(today, today, deduction -> true, tally);

        int overdue = reportOverdueLodgements(awaiting, now);
        return new ResponseSweepResult(from, today, awaiting.size(), tally.batches, tally.records,
                List.copyOf(tally.failures), overdue);
    }

    /**
     * The first day to read: the oldest waiting lodgement's day, less one day of margin (the day is
     * stamped on our clock, and Ndasenda dates its responses on its own), never further back than
     * {@code lookbackDays} and never after today. Today alone when nothing is waiting.
     */
    static LocalDate responseWindowStart(LocalDate today, Collection<NdasendaAwaitingLoan> awaiting, int lookbackDays) {
        LocalDate floor = today.minusDays(Math.max(0, lookbackDays));
        return awaiting.stream()
                .map(NdasendaAwaitingLoan::lodgedAt)
                .filter(Objects::nonNull)
                // The stamp's UTC day: never later than its market day in any market we serve (all
                // are ahead of UTC), so the window can only start earlier, never miss a day.
                .map(LocalDateTime::toLocalDate)
                .min(Comparator.naturalOrder())
                .map(oldest -> oldest.minusDays(1))
                .map(start -> start.isBefore(floor) ? floor : start)
                .map(start -> start.isAfter(today) ? today : start)
                .orElse(today);
    }

    private void readResponses(LocalDate from, LocalDate to, Predicate<NdasendaDeduction> take, SweepTally tally) {
        List<NdasendaDeductionBatch> batches;
        try {
            batches = fetchBatchResponsesByDate(from, to);
        } catch (Exception ex) {
            String cause = describeFetchFailure(ex);
            log.error("NDASENDA RESPONSE FETCH FAILED: could not list the response batches from {} to {} ({})"
                            + " - their responses are unread this run, not absent; no loan was changed for them",
                    from, to, cause);
            tally.failures.add("batches " + from + ".." + to + ": " + cause);
            return;
        }
        for (NdasendaDeductionBatch listed : nullToEmpty(batches)) {
            // Keep the batch id beside each deduction so an unmatched one can be traced back to it.
            String batchId = listed.getId();
            List<NdasendaDeductionBatch> responses;
            try {
                responses = fetchDeductionResponsesByBatchId(batchId);
            } catch (Exception ex) {
                String cause = describeFetchFailure(ex);
                log.error("NDASENDA RESPONSE FETCH FAILED: could not read the responses of batch {}, listed from {} to {}"
                                + " ({}) - they are unread this run, not absent; no loan was changed for them",
                        batchId, from, to, cause);
                tally.failures.add("batch " + batchId + ": " + cause);
                continue;
            }
            tally.batches++;
            nullToEmpty(responses).stream()
                    .flatMap(batch -> nullToEmpty(batch.getDeductions()).stream())
                    .filter(take)
                    .forEach(deduction -> {
                        tally.records++;
                        processDeductionRequestResponse(batchId, deduction);
                    });
        }
    }

    /**
     * The cause's type and HTTP status, never its message: a response body or a JSON parser's message
     * can quote a deduction record (national ID, EC number), and the request URL carries our deduction
     * code.
     */
    static String describeFetchFailure(Throwable ex) {
        StringBuilder cause = new StringBuilder(ex.getClass().getSimpleName());
        if (ex instanceof RestClientResponseException http) {
            cause.append(" HTTP ").append(http.getStatusCode().value());
        }
        Throwable root = ExceptionUtils.getRootCause(ex);
        if (root != null && root != ex) {
            cause.append(" caused by ").append(root.getClass().getSimpleName());
        }
        return cause.toString();
    }

    private static Long loanIdOrNull(String reference) {
        try {
            return Long.parseLong(reference);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Counts for one run's result; one run, one thread. */
    private static final class SweepTally {
        private int batches;
        private int records;
        private final List<String> failures = new ArrayList<>();
    }

    /**
     * Raised once per loan: the loan records when it was reported, so a lodgement that stays unanswered
     * does not re-audit on every run. The mark is saved before the audit row is written, so a save
     * that fails claims nothing and the next run tries again, while an audit that fails after it still
     * leaves the ERROR line.
     */
    int reportOverdueLodgements(List<NdasendaAwaitingLoan> awaiting, LocalDateTime now) {
        int afterDays = Math.max(0, ndasendaProps.getResponses().getOverdueAfterDays());
        LocalDateTime cutoff = now.minusDays(afterDays);
        int reported = 0;
        for (NdasendaAwaitingLoan candidate : awaiting) {
            LocalDateTime lodgedAt = candidate.lodgedAt();
            if (candidate.responseOverdueAt() != null || lodgedAt == null || !lodgedAt.isBefore(cutoff)) {
                continue;
            }
            try {
                if (reportOverdue(candidate.id(), lodgedAt, afterDays, now)) {
                    reported++;
                }
            } catch (Exception ex) {
                log.error("Could not report loan {} overdue for Ndasenda's answer ({}); the next run tries again",
                        candidate.id(), ex.getClass().getSimpleName(), ex);
            }
        }
        return reported;
    }

    private boolean reportOverdue(Long loanId, LocalDateTime lodgedAt, int afterDays, LocalDateTime now) {
        // Re-read: this run may just have applied the loan's answer, and the list's check was a mirror.
        Loan loan = loanRepository.findById(loanId).orElse(null);
        if (loan == null || !awaitingNdasendaOutcome(loan) || loan.getNdasendaResponseOverdueAt() != null) {
            return false;
        }
        loan.setNdasendaResponseOverdueAt(now);
        loanRepository.save(loan);

        long waitedDays = ChronoUnit.DAYS.between(lodgedAt, now);
        log.error("NDASENDA RESPONSE OVERDUE: loan {} reference {} ({}) batch {} ec {} was lodged at {} and Ndasenda"
                        + " has not answered in {} day(s) (threshold {}) - check the deduction on Ndasenda's portal"
                        + " (audited once)",
                loan.getId(), loan.getReference(), loan.getLoanApprovalStatus(), loan.getBatchNumber(),
                maskEcNumber(loan.getEcNumber()), lodgedAt, waitedDays, afterDays);
        // Identifiers only, as for every NDASENDA_* event; correlated on the lodgement's batch.
        record(RESPONSE_OVERDUE, String.valueOf(loan.getId()), AuditLog.builder()
                .eventType(RESPONSE_OVERDUE)
                .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                .actorId(SYSTEM_ACTOR).channelUsed("system")
                .detail("loanStatus=" + loan.getLoanApprovalStatus() + " lodgedAt=" + lodgedAt
                        + " waitedDays=" + waitedDays + " thresholdDays=" + afterDays
                        + " reference=" + loan.getReference() + " batch=" + loan.getBatchNumber()
                        + " ecNumber=" + maskEcNumber(loan.getEcNumber()))
                .correlationId(loan.getBatchNumber()));
        return true;
    }

    public List<NdasendaDeductionBatch> findBatches(FindNdasendaBatchRequest request) {
        log.info("Finding Ndasenda batches: {}", request);
        try {
            LocalDate fromDate = request.getFromDate() == null ? LocalDate.MIN : request.getFromDate();
            LocalDate toDate = request.getToDate() == null ? LocalDate.MAX : request.getToDate();
            return findBatchRequestsByDate(fromDate, toDate)
                    .stream()
                    .filter(matchesBatchFilter(request))
                    .map(this::populateCustomerInformation)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.warn("Error finding batches: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    private Predicate<NdasendaDeductionBatch> matchesBatchFilter(FindNdasendaBatchRequest request) {
        return b -> loanBatchService.existsByBatchNumber(b.getId())
                && (request.getBatchStatus() == null || b.getStatus() == request.getBatchStatus());
    }


    private NdasendaDeductionBatch populateCustomerInformation(NdasendaDeductionBatch request) {
        request.getDeductions().stream()
                .forEach(d -> loanRepository.findById(Long.parseLong(d.getReference()))
                        .ifPresent(l -> {
                            d.setFirstName(l.getFirstName());
                            d.setLastName(l.getLastName());
                            d.setMobileNumber(l.getMobileNumber());
                        }));
        return request;
    }

    /**
     * Commits Ndasenda's open deduction batch. Each outcome is logged as what it is: this used to log a
     * network failure as "No pending batch to commit", so a Ndasenda outage read as a quiet day, while
     * the 404 that does mean nothing was open was logged as an error. Committing again is safe, since
     * a batch already committed is no longer open, so every failure is left to the next scheduled run.
     */
    public void commitDeductionRequestsUntilNow() {
        log.info("Committing Ndasenda's open deduction batch");
        try {
            NdasendaDeductionBatch committed = executeWithTokenRefreshRetry(() -> {
                ResponseEntity<NdasendaDeductionBatch> response = restTemplate.exchange(
                        ndasendaProps.getCommitDeductionsEndpoint(),
                        POST,
                        new HttpEntity<>(getHttpHeaders()),
                        NdasendaDeductionBatch.class,
                        ndasendaProps.getDeductionCode());
                return response.getBody();
            });
            log.info("Committed Ndasenda deduction batch {} ({} record(s), status {})",
                    committed == null ? null : committed.getId(),
                    committed == null ? null : committed.getRecordsCount(),
                    committed == null ? null : committed.getStatus());
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("No open deduction batch to commit (Ndasenda answered 404)");
        } catch (ResourceAccessException ex) {
            log.error("NDASENDA COMMIT FAILED: could not reach Ndasenda ({}); the open batch, if any, was not"
                    + " confirmed committed and the next scheduled run commits it", ex.getMessage());
        } catch (Exception ex) {
            log.error("NDASENDA COMMIT FAILED: the open batch, if any, was not confirmed committed; the next"
                    + " scheduled run commits it", ex);
        }
    }


    private List<NdasendaDeductionBatch> findBatchRequestsByDate(LocalDate fromDate, LocalDate toDate) {
        log.info("Find batches requests from: {} to {}", fromDate, toDate);
        try {
            return executeWithTokenRefreshRetry(() -> {
                ResponseEntity<List<NdasendaDeductionBatch>> response = restTemplate.exchange(
                        ndasendaProps.getDeductionRequestsByDateRangeEndpoint(),
                        HttpMethod.GET,
                        new HttpEntity<>(getHttpHeaders()),
                        new ParameterizedTypeReference<List<NdasendaDeductionBatch>>() {
                        },
                        DEDUCTION_DATE_FORMAT.format(fromDate),
                        DEDUCTION_DATE_FORMAT.format(toDate),
                        ndasendaProps.getDeductionCode());
                return response.getBody();
            });
        } catch (Exception ex) {
            log.error("Error finding batch requests by date", ex);
            return Collections.emptyList();
        }
    }


    /** Throws on failure: the response job must tell a failed read from an empty one. */
    private List<NdasendaDeductionBatch> fetchBatchResponsesByDate(LocalDate fromDate, LocalDate toDate) throws Exception {
        log.info("Find batches from: {} to {}", fromDate, toDate);
        return executeWithTokenRefreshRetry(() -> {
            ResponseEntity<List<NdasendaDeductionBatch>> response = restTemplate.exchange(
                    ndasendaProps.getDeductionResponsesByDateRangeEndpoint(),
                    HttpMethod.GET,
                    new HttpEntity<>(getHttpHeaders()),
                    new ParameterizedTypeReference<List<NdasendaDeductionBatch>>() {
                    },
                    DEDUCTION_DATE_FORMAT.format(fromDate),
                    DEDUCTION_DATE_FORMAT.format(toDate),
                    ndasendaProps.getDeductionCode());
            return response.getBody();
        });
    }

    /**
     * Executes the given supplier function with token refresh retry logic.
     * If an authentication-related exception occurs (UnknownContentTypeException or UNAUTHORIZED status),
     * it refreshes the token and retries once.
     *
     * @param supplier The function to execute
     * @param <T> The return type of the function
     * @return The result of the function
     * @throws Exception If an exception occurs that is not authentication-related or if the retry also fails
     */
    private <T> T executeWithTokenRefreshRetry(Supplier<T> supplier) throws Exception {
        try {
            return supplier.get();
        } catch (UnknownContentTypeException | HttpClientErrorException e) {
            boolean shouldRetry = e instanceof UnknownContentTypeException || 
                (e instanceof HttpClientErrorException && ((HttpClientErrorException) e).getStatusCode() == HttpStatus.UNAUTHORIZED);

            if (shouldRetry) {
                log.warn("Authentication error occurred, refreshing token and retrying...", e);
                ndasendaAuthService.refreshToken();
                return supplier.get();
            }
            throw e;
        }
    }

    public List<NdasendaDeductionBatch> findDeductionResponsesByBatchId(String batchId) {
        try {
            return fetchDeductionResponsesByBatchId(batchId);
        } catch (Exception ex) {
            log.error("Error finding deduction responses by batch ID", ex);
            return Collections.emptyList();
        }
    }

    /** As {@link #findDeductionResponsesByBatchId}, but a failure is thrown rather than read as "none". */
    private List<NdasendaDeductionBatch> fetchDeductionResponsesByBatchId(String batchId) throws Exception {
        log.info("Find batch id: {}", batchId);
        return executeWithTokenRefreshRetry(() -> {
            ResponseEntity<List<NdasendaDeductionBatch>> response = restTemplate.exchange(
                    ndasendaProps.getDeductionResponsesByBatchId(),
                    GET,
                    new HttpEntity<>(getHttpHeaders()),
                    new ParameterizedTypeReference<List<NdasendaDeductionBatch>>() {
                    },
                    batchId);
            return response.getBody();
        });
    }

    public NdasendaDeductionBatch findBatchById(String batchId) {
        log.info("Find batch id: {}", batchId);
        try {
            return executeWithTokenRefreshRetry(() -> {
                ResponseEntity<NdasendaDeductionBatch> response = restTemplate.exchange(
                        ndasendaProps.getFindBatchEndpoint(),
                        GET, 
                        new HttpEntity<>(getHttpHeaders()),
                        NdasendaDeductionBatch.class,
                        batchId);
                return response.getBody();
            });
        } catch (Exception ex) {
            log.error("Error finding batch by ID", ex);
            return null;
        }
    }

    /**
     * A response we cannot tie to a loan is a stop order on someone's salary that our books do not
     * know about, so it is never dropped quietly: it is logged at ERROR with the identifiers needed
     * to find it at Ndasenda, and audited. The rest of the batch always carries on. A response for a
     * loan no longer awaiting Ndasenda is never applied; if it disagrees, it is reported the same way.
     * Nor is a record that does not answer a lodgement (CHANGE, DELETE), or one whose EC number is not
     * the loan's: the reference alone is only our loan id. Both are reported, once.
     */
    void processDeductionRequestResponse(String batchId, NdasendaDeduction response) {
        // Not the whole record: its toString carries the national ID and EC number in full.
        log.info("Processing deduction response {} in batch {}: reference {} type {} status {} ec {}",
                response.getId(), batchId, response.getReference(), response.getType(), response.getStatus(),
                maskEcNumber(response.getEcNumber()));
        try {
            if (!isLodgementAnswer(response)) {
                reportIgnored(batchId, response);
                return;
            }

            long id;
            try {
                id = Long.parseLong(response.getReference());
            } catch (NumberFormatException ex) {
                reportUnmatched(batchId, response, "invalid_reference");
                return;
            }

            loanRepository.findById(id)
                    .ifPresentOrElse(loan -> applyResponse(batchId, response, loan),
                            () -> reportUnmatched(batchId, response, "unknown_loan"));
        } catch (Exception ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.error("NDASENDA RESPONSE FAILED: deduction {} batch {} reference {} status {} ec {} (audited)",
                    response.getId(), batchId, response.getReference(), response.getStatus(),
                    maskEcNumber(response.getEcNumber()), ex);
            audit(RESPONSE_FAILED, batchId, response, "error=" + reason);
        }
    }

    private void applyResponse(String batchId, NdasendaDeduction response, Loan loan) {
        // The reference is only our loan id, so check the record is this loan's before anything else -
        // the conflict path included, which can flag the loan's deduction for cancellation.
        if (!ecNumbersAgree(response.getEcNumber(), loan.getEcNumber())) {
            reportMismatch(batchId, response, loan);
            return;
        }

        LoanApprovalStatus outcome = response.getStatus().getApprovalStatus();
        if (outcome == loan.getLoanApprovalStatus()) {
            log.info("Loan already updated: {}", outcome);
            return;
        }
        // The job re-reads its batches every 10 minutes, and one reference can carry
        // several records (a FAILED and a SUCCESS after a duplicate lodgement). Only a loan still
        // waiting on Ndasenda takes its answer: applied later, it would flip a decided loan, or
        // re-queue a booked or paid one for booking and payment.
        if (!awaitingNdasendaOutcome(loan)) {
            reportConflict(batchId, response, loan);
            return;
        }

        loan.setLoanApprovalStatus(outcome);
        loan.setDateApproved(LocalDateTime.now(ZoneOffset.UTC));
        loan.setApprovalReference(response.getId());
        // A FAILED lodgement flagged for cancellation now has Ndasenda's answer, which settles it.
        deductionCancellationService.withdraw(loan, String.valueOf(response.getStatus()), SYSTEM_ACTOR);

        // Account and disbursement fields belong to the booking jobs once the account has left PENDING.
        if (LoanApprovalStatus.APPROVED == outcome
                && (loan.getLoanAccountStatus() == null || loan.getLoanAccountStatus() == LoanAccountStatus.PENDING)) {
            loan.setDisbursementAttempts(0);
            loan.setNextDisbursementAttemptDate(LocalDateTime.now(ZoneOffset.UTC));
            loan.setLoanAccountStatus(LoanAccountStatus.PENDING);
        }

        // Ndasenda's reason is for staff: the customer's decline only tells
        // them to contact us, so the reason has to be on the loan when they do.
        if (LoanApprovalStatus.REJECTED == outcome && StringUtils.isNotBlank(response.getMessage())) {
            loan.setLoanStatusMessage(StringUtils.left(response.getMessage(), 250));
        }

        loanRepository.save(loan);

        // Reference and amount only: Ndasenda's text never reaches the applicant. SSB's approval is a stage of
        // its own (FR-SSB-016), told apart from Credit's: the applicant hears the deduction is confirmed and the
        // application is being assessed, and hears the outcome of that separately.
        LoanNotice notice = NOTICES.get(outcome);
        if (notice != null) {
            loanNotificationService.notify(loan, notice);
        }
    }

    /**
     * PROCESSING is the normal wait. FAILED with a lodgement reference is the ambiguous case: the
     * lodgement reached Ndasenda before a later step threw, so Ndasenda's answer is still the one
     * that counts - unless an operator has already cancelled that deduction, which no answer undoes.
     */
    static boolean awaitingNdasendaOutcome(Loan loan) {
        if (loan.getLoanApprovalStatus() == LoanApprovalStatus.PROCESSING) {
            return true;
        }
        return loan.getLoanApprovalStatus() == LoanApprovalStatus.FAILED
                && DeductionCancellationService.wasLodged(loan)
                && loan.getDeductionCancellationStatus() != DeductionCancellationStatus.CANCELLED_EXTERNALLY;
    }

    /**
     * We only ever lodge NEW deductions ({@link #fromLoanRequest}). A CHANGE or DELETE record answers an
     * amendment or a cancellation - an operator's on Ndasenda's portal, say - under the same reference,
     * and its SUCCESS or FAILED says nothing about the lodgement. A record with no type is treated as
     * the lodgement answer it has always been taken for: nothing here shows Ndasenda echoing the type on
     * a response, and refusing untyped records would stop every approval.
     */
    static boolean isLodgementAnswer(NdasendaDeduction response) {
        return response.getType() == null || response.getType() == NdasendaDeductionType.NEW;
    }

    /**
     * Checked only when both sides carry an EC number, compared the way an application normalises one
     * (separators and spaces dropped, case ignored): rows stored before that normalisation, or an echo
     * formatted differently, must not read as someone else's record.
     */
    static boolean ecNumbersAgree(String responseEcNumber, String loanEcNumber) {
        if (StringUtils.isBlank(responseEcNumber) || StringUtils.isBlank(loanEcNumber)) {
            return true;
        }
        return TextUtils.trimSpecialCharacters(responseEcNumber).equalsIgnoreCase(TextUtils.trimSpecialCharacters(loanEcNumber));
    }

    private void reportIgnored(String batchId, NdasendaDeduction response) {
        if (alreadyReported(RESPONSE_IGNORED, response)) {
            log.debug("Deduction response {} ({}) in batch {} was already reported as ignored",
                    response.getId(), response.getType(), batchId);
            return;
        }
        log.warn("NDASENDA RESPONSE IGNORED: deduction {} batch {} reference {} type {} status {} ec {}"
                        + " - it answers an amendment or cancellation, not a lodgement, so no loan was changed (audited once)",
                response.getId(), batchId, response.getReference(), response.getType(), response.getStatus(),
                maskEcNumber(response.getEcNumber()));
        audit(RESPONSE_IGNORED, batchId, response, "reason=not_a_lodgement type=" + response.getType());
    }

    private void reportMismatch(String batchId, NdasendaDeduction response, Loan loan) {
        if (alreadyReported(RESPONSE_MISMATCH, response)) {
            log.debug("Deduction response {} in batch {} was already reported as a mismatch for loan {}",
                    response.getId(), batchId, loan.getId());
            return;
        }
        log.error("NDASENDA RESPONSE MISMATCH: deduction {} batch {} reference {} status {} carries ec {} but loan {} ({})"
                        + " has ec {} - it is not this loan's record, so nothing was applied (audited once)",
                response.getId(), batchId, response.getReference(), response.getStatus(),
                maskEcNumber(response.getEcNumber()), loan.getId(), loan.getLoanApprovalStatus(),
                maskEcNumber(loan.getEcNumber()));
        audit(RESPONSE_MISMATCH, batchId, response, "reason=ec_number_mismatch loanId=" + loan.getId()
                + " loanStatus=" + loan.getLoanApprovalStatus() + " loanEcNumber=" + maskEcNumber(loan.getEcNumber()));
    }

    /**
     * An ignored or mismatched record leaves its loan waiting, so it stays in the window and is read
     * again every run for as long as the loan waits: it is reported the first time only. A lookup that
     * fails reports it again - twice beats never.
     */
    private boolean alreadyReported(String eventType, NdasendaDeduction response) {
        try {
            return auditService.hasRecorded(eventType, DEDUCTION_ENTITY, response.getId());
        } catch (Exception ex) {
            log.warn("Could not check whether Ndasenda deduction {} was already reported as {} ({}); reporting it",
                    response.getId(), eventType, ex.getClass().getSimpleName());
            return false;
        }
    }

    private void reportConflict(String batchId, NdasendaDeduction response, Loan loan) {
        String held = "loanId=" + loan.getId() + " loanStatus=" + loan.getLoanApprovalStatus()
                + " internalApproval=" + loan.getInternalApprovalStatus()
                + " accountStatus=" + loan.getLoanAccountStatus()
                + " disbursementStatus=" + loan.getDisbursementStatus()
                + " cancellation=" + loan.getDeductionCancellationStatus();
        log.error("NDASENDA RESPONSE CONFLICT: deduction {} batch {} reference {} status {} ec {} disagrees with {}"
                        + " - the loan is no longer awaiting Ndasenda, so nothing was applied (audited)",
                response.getId(), batchId, response.getReference(), response.getStatus(),
                maskEcNumber(response.getEcNumber()), held);
        audit(RESPONSE_CONFLICT, batchId, response, held);

        // Ndasenda accepting the deduction of a loan we have closed (declined, or a lodgement we gave
        // up on) leaves a live stop order on a salary for a loan that will never be paid. The loan
        // stays exactly as it is; only the cancellation is tracked.
        boolean closed = loan.getLoanApprovalStatus() == LoanApprovalStatus.REJECTED
                || loan.getLoanApprovalStatus() == LoanApprovalStatus.FAILED;
        if (response.getStatus() == NdasendaDeductionStatus.SUCCESS && closed
                && deductionCancellationService.markRequired(loan,
                DeductionCancellationService.REASON_ACCEPTED_AFTER_CLOSE, SYSTEM_ACTOR, "system")) {
            loanRepository.save(loan);
        }
    }

    private void reportUnmatched(String batchId, NdasendaDeduction response, String reason) {
        log.error("NDASENDA RESPONSE UNMATCHED ({}): deduction {} batch {} reference {} status {} ec {}"
                        + " - no loan here accounts for this deduction (audited)",
                reason, response.getId(), batchId, response.getReference(), response.getStatus(),
                maskEcNumber(response.getEcNumber()));
        audit(RESPONSE_UNMATCHED, batchId, response, "reason=" + reason);
    }

    private void audit(String eventType, String batchId, NdasendaDeduction response, String outcome) {
        // Identifiers only: the deduction id + batch find the full record at Ndasenda, so the
        // national ID is never copied here and the EC number keeps only its last 3 characters.
        record(eventType, response.getId(), AuditLog.builder()
                .eventType(eventType)
                .entityType(DEDUCTION_ENTITY).entityId(response.getId())
                .actorId(SYSTEM_ACTOR).channelUsed("system")
                .detail(outcome + " reference=" + response.getReference() + " status=" + response.getStatus()
                        + " ecNumber=" + maskEcNumber(response.getEcNumber()))
                .payloadHash(AuditService.sha256Hex(String.valueOf(response)))
                .correlationId(batchId));
    }

    private void record(String eventType, String entityId, AuditLog.AuditLogBuilder entry) {
        try {
            auditService.record(entry);
        } catch (Exception ex) {
            // AuditService swallows write failures, but its REQUIRES_NEW proxy can still throw while
            // opening the transaction; the log line above is the evidence, and the run must carry on.
            log.error("Audit of {} ({}) failed", entityId, eventType, ex);
        }
    }

    /** Keeps only the last 3 characters; anything that short is masked whole. */
    public static String maskEcNumber(String ecNumber) {
        if (ecNumber == null) {
            return null;
        }
        int visible = 3;
        if (ecNumber.length() <= visible) {
            return "*".repeat(ecNumber.length());
        }
        return "*".repeat(ecNumber.length() - visible) + ecNumber.substring(ecNumber.length() - visible);
    }

    private NdasendaDeduction fromLoanRequest(LoanApprovalRequest request, LocalDate startDate, LocalDate endDate) {
        return NdasendaDeduction.builder()
                .amountInCents(toCents(request.getMonthlyInstallment()))
                .ecNumber(request.getEcnumber())
                .idNumber(request.getIdNumber())
                .startDate(formatDate(startDate))
                .endDate(formatDate(endDate))
                .type(NdasendaDeductionType.NEW)
                .reference(request.getReference())
                .build();
    }


    /**
     * Rounds to the cent rather than truncating ({@code intValue()} dropped any
     * sub-cent remainder: 10.005 went out as 1000), and converts exactly, so an
     * amount too large for an int fails here instead of wrapping to a wrong one.
     */
    private int toCents(BigDecimal amount) {
        return amount.multiply(CENTS).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private String formatDate(LocalDate localDate) {
        return DEDUCTION_DATE_FORMAT.format(localDate);
    }

    private HttpHeaders getHttpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ndasendaAuthService.getAccessToken());
        return headers;
    }
}
