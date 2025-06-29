package zw.co.reikan.loans.core.ndasenda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.UnknownContentTypeException;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanBatchService;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static zw.co.reikan.loans.core.loan.SmsMessages.*;

@Slf4j
@RequiredArgsConstructor
@Service
@Primary
@Profile("!dummy-loan-approval")
public class NdasendaLoanApprovalServiceImpl implements LoanApprovalService {

    private static final BigDecimal CENTS = new BigDecimal("100");
    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final RestTemplate restTemplate;
    private final NdasendaAuthServiceImpl ndasendaAuthService;
    private final NdasendaParameters ndasendaProps;
    private final LoanRepository loanRepository;
    private final LoanBatchService loanBatchService;
    private final NotificationService notificationService;

    Map<LoanApprovalStatus, String> smsMessages = Map.of(LoanApprovalStatus.APPROVED, APPROVED_LOAN,
            LoanApprovalStatus.REJECTED, REJECTED_LOAN,
            LoanApprovalStatus.PROCESSING, PROCESSING_LOAN
    );

    public LoanApprovalResponse requestApproval(LoanApprovalRequest request) {
        log.info("Processing Ndasenda loan deduction approval request {}", request);

        LocalDate loanStartDate = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        LocalDate endDate = loanStartDate.plusMonths(request.getTenor() - 1); //subtract 1 because month is inclusive
        LocalDate loanEndDate = endDate.withDayOfMonth(endDate.lengthOfMonth());

        final NdasendaDeduction deductionRequest = fromLoanRequest(request, loanStartDate, loanEndDate);
        final List<NdasendaDeduction> deductions = List.of(deductionRequest);
        final NdasendaDeductionsBatchRequest batch = NdasendaDeductionsBatchRequest.builder()
                .totalAmountInCents(deductionRequest.getAmountInCents())
                .recordsCount(deductions.size())
                .deductionCode(ndasendaProps.getDeductionCode())
                .securityToken(ndasendaProps.getSecurityCode())
                .deductions(deductions)
                .build();

        try {
            NdasendaDeductionsBatchRequest deductionsBatchResponse = executeWithTokenRefreshRetry(() -> {
                HttpEntity<NdasendaDeductionsBatchRequest> requestEntity = new HttpEntity<>(batch, getHttpHeaders());
                ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(
                        ndasendaProps.getDeductionRequestsEndpoint(),
                        POST, 
                        requestEntity, 
                        NdasendaDeductionsBatchRequest.class);
                return response.getBody();
            });

            return LoanApprovalResponse.builder()
                    .status(LoanApprovalStatus.PROCESSING)
                    .batchNumber(deductionsBatchResponse.getId())
                    .startDate(loanStartDate)
                    .endDate(loanEndDate)
                    .build();
        } catch (Exception ex) {
            log.error("Error requesting loan approval", ex);
            throw new RuntimeException("Failed to request loan approval", ex);
        }
    }

    public void processDeductionResponses(LocalDate today) {
        log.info("Process deduction responses for: {}", today);
        findBatchResponsesByDate(today, today)
                .stream()
                .map(NdasendaDeductionsBatchRequest::getId)
                .map(this::findDeductionResponsesByBatchId)
                .flatMap(responses -> responses.stream())
                .flatMap(batch -> batch.getDeductions().stream())
                .forEach(this::processDeductionRequestResponse);
    }

    public List<NdasendaDeductionsBatchRequest> findBatches(FindNdasendaBatchRequest request) {
        log.info("Finding Ndasenda batches: {}", request);
        try {
            LocalDate fromDate = request.getFromDate() == null ? LocalDate.MIN : request.getFromDate();
            LocalDate toDate = request.getToDate() == null ? LocalDate.MAX : request.getToDate();
            return findBatchRequestsByDate(fromDate, toDate)
                    .stream()
                    .filter(getNdasendaDeductionsBatchRequestPredicate(request))
                    .map(this::populateCustomerInformation)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.warn("Error finding batches: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    private Predicate<NdasendaDeductionsBatchRequest> getNdasendaDeductionsBatchRequestPredicate(FindNdasendaBatchRequest request) {
        return b -> loanBatchService.existsByBatchNumber(b.getId())
                && (request.getBatchStatus() == null || b.getStatus() == request.getBatchStatus());
    }


    private NdasendaDeductionsBatchRequest populateCustomerInformation(NdasendaDeductionsBatchRequest request) {
        request.getDeductions().stream()
                .forEach(d -> loanRepository.findById(Long.parseLong(d.getReference()))
                        .ifPresent(l -> {
                            d.setFirstName(l.getFirstName());
                            d.setLastName(l.getLastName());
                            d.setMobileNumber(l.getMobileNumber());
                        }));
        return request;
    }

    public void commitDeductionRequestsUntilNow() {
        log.info("Committing batch id");
        try {
            executeWithTokenRefreshRetry(() -> {
                ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(
                        ndasendaProps.getCommitDeductionsEndpoint(),
                        POST, 
                        new HttpEntity<>(getHttpHeaders()),
                        NdasendaDeductionsBatchRequest.class,
                        ndasendaProps.getDeductionCode());
                return response.getBody();
            });
        } catch (ResourceAccessException rae) {
            log.warn("No pending batch to commit");
        } catch (HttpClientErrorException.NotFound ex) {
            log.warn("Error committing deduction batch: {}", ex.getMessage());
        } catch (Exception ex) {
            log.error("Error committing deduction batch: ", ex);
        }
    }


    private List<NdasendaDeductionsBatchRequest> findBatchRequestsByDate(LocalDate fromDate, LocalDate toDate) {
        log.info("Find batches requests from: {} to {}", fromDate, toDate);
        try {
            return executeWithTokenRefreshRetry(() -> {
                ResponseEntity<List<NdasendaDeductionsBatchRequest>> response = restTemplate.exchange(
                        ndasendaProps.getDeductionRequestsByDateRangeEndpoint(),
                        HttpMethod.GET,
                        new HttpEntity<>(getHttpHeaders()),
                        new ParameterizedTypeReference<List<NdasendaDeductionsBatchRequest>>() {
                        },
                        dateTimeFormatter.format(fromDate),
                        dateTimeFormatter.format(toDate),
                        ndasendaProps.getDeductionCode());
                return response.getBody();
            });
        } catch (Exception ex) {
            log.error("Error finding batch requests by date", ex);
            return Collections.emptyList();
        }
    }


    private List<NdasendaDeductionsBatchRequest> findBatchResponsesByDate(LocalDate fromDate, LocalDate toDate) {
        log.info("Find batches from: {} to {}", fromDate, toDate);
        try {
            return executeWithTokenRefreshRetry(() -> {
                ResponseEntity<List<NdasendaDeductionsBatchRequest>> response = restTemplate.exchange(
                        ndasendaProps.getDeductionResponsesByDateRangeEndpoint(),
                        HttpMethod.GET,
                        new HttpEntity<>(getHttpHeaders()),
                        new ParameterizedTypeReference<List<NdasendaDeductionsBatchRequest>>() {
                        },
                        dateTimeFormatter.format(fromDate),
                        dateTimeFormatter.format(toDate),
                        ndasendaProps.getDeductionCode());
                return response.getBody();
            });
        } catch (Exception ex) {
            log.error("Error finding batch responses by date", ex);
            return Collections.emptyList();
        }
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

    public List<NdasendaDeductionsBatchRequest> findDeductionResponsesByBatchId(String batchId) {
        log.info("Find batch id: {}", batchId);
        try {
            return executeWithTokenRefreshRetry(() -> {
                ResponseEntity<List<NdasendaDeductionsBatchRequest>> response = restTemplate.exchange(
                        ndasendaProps.getDeductionResponsesByBatchId(),
                        GET, 
                        new HttpEntity<>(getHttpHeaders()),
                        new ParameterizedTypeReference<List<NdasendaDeductionsBatchRequest>>() {
                        }, 
                        batchId);
                return response.getBody();
            });
        } catch (Exception ex) {
            log.error("Error finding deduction responses by batch ID", ex);
            return Collections.emptyList();
        }
    }

    public NdasendaDeductionsBatchRequest findBatchById(String batchId) {
        log.info("Find batch id: {}", batchId);
        try {
            return executeWithTokenRefreshRetry(() -> {
                ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(
                        ndasendaProps.getFindBatchEndpoint(),
                        GET, 
                        new HttpEntity<>(getHttpHeaders()),
                        NdasendaDeductionsBatchRequest.class,
                        batchId);
                return response.getBody();
            });
        } catch (Exception ex) {
            log.error("Error finding batch by ID", ex);
            return null;
        }
    }

    private void processDeductionRequestResponse(NdasendaDeduction response) {
        log.info("Processing deduction response: {}", response);
        try {
            long id;
            try {
                id = Long.parseLong(response.getReference());
            } catch (NumberFormatException ex) {
                log.warn("Invalid reference: {}", response.getReference());
                return;
            }

            loanRepository.findById(id)
                    .ifPresent(loan -> {
                        if (response.getStatus().getApprovalStatus() == loan.getLoanApprovalStatus()) {
                            log.info("Loan already updated: {}", response.getStatus().getApprovalStatus());
                            return;
                        }

                        loan.setLoanApprovalStatus(response.getStatus().getApprovalStatus());
                        loan.setDateApproved(LocalDateTime.now());
                        loan.setApprovalReference(response.getId());

                        if (LoanApprovalStatus.APPROVED == response.getStatus().getApprovalStatus()) {
                            loan.setDisbursementAttempts(0);
                            loan.setNextDisbursementAttemptDate(LocalDateTime.now());
                            loan.setLoanAccountStatus(LoanAccountStatus.PENDING);
                        }

                        final String text = String.format(smsMessages.get(loan.getLoanApprovalStatus()),
                                String.format("%09d", loan.getId()), loan.getDisbursedAmount(), response.getMessage());

                        //Do not send notification for SSB approval. SMS will be sent on internal approval
                        if (LoanApprovalStatus.APPROVED != response.getStatus().getApprovalStatus()) {
                            notificationService.sendSms(loan.getMobileNumber(), text);
                        }

                        loanRepository.save(loan);
                    });
        } catch (Exception ex) {
            log.error("", ex);
        }
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


    private int toCents(BigDecimal amount) {
        return amount.multiply(CENTS).intValue();
    }

    private String formatDate(LocalDate localDate) {
        return dateTimeFormatter.format(localDate);
    }

    private HttpHeaders getHttpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ndasendaAuthService.getAccessToken());
        return headers;
    }
}
