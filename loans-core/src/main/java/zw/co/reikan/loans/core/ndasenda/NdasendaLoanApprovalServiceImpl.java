package zw.co.reikan.loans.core.ndasenda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
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
import java.util.stream.Collectors;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

@Slf4j
@RequiredArgsConstructor
@Service
@Primary
public class NdasendaLoanApprovalServiceImpl implements LoanApprovalService {

    private static final BigDecimal CENTS = new BigDecimal("100");
    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final RestTemplate restTemplate;
    private final NdasendaAuthServiceImpl ndasendaAuthService;
    private final NdasendaParameters ndasendaProps;
    private final LoanRepository loanRepository;
    private final LoanBatchService loanBatchService;
    private final NotificationService notificationService;

    Map<LoanApprovalStatus, String> smsMessages = Map.of(LoanApprovalStatus.APPROVED, "CONGRATULATIONS! Your loan has been approved. Funds will be disbursed within 2 hours. Ref: %s.",
            LoanApprovalStatus.REJECTED, "Loan application Ref: %s has been rejected. %s",
            LoanApprovalStatus.PROCESSING, "Loan application received. Your request is being processed. We'll update you soon. Ref: %s"
    );

    public LoanApprovalResponse requestApproval(LoanApprovalRequest request) {

        log.info("Requesting loan deduction");

        LocalDate loanStartDate = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        LocalDate endDate = loanStartDate.plusMonths(request.getTenor());
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

        HttpEntity<NdasendaDeductionsBatchRequest> requestEntity = new HttpEntity<>(batch, getHttpHeaders());

        ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(ndasendaProps.getDeductionRequestsEndpoint(),
                POST, requestEntity, NdasendaDeductionsBatchRequest.class);

        NdasendaDeductionsBatchRequest deductionsBatchResponse = response.getBody();

        return LoanApprovalResponse.builder()
                .status(LoanApprovalStatus.PROCESSING)
                .batchNumber(deductionsBatchResponse.getId())
                .startDate(loanStartDate)
                .endDate(loanEndDate)
                .build();
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
            return findBatchRequestsByDate(request.getFromDate(), request.getToDate())
                    .stream()
                    .filter(getNdasendaDeductionsBatchRequestPredicate(request))
                    .map(this::populateCustomerInformation)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.error("", ex);
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
            restTemplate.exchange(ndasendaProps.getCommitDeductionsEndpoint(),
                    POST, new HttpEntity<>(getHttpHeaders()),
                    NdasendaDeductionsBatchRequest.class,
                    ndasendaProps.getDeductionCode());
        } catch (ResourceAccessException rae) {
            log.warn("No pending batch to commit");
        } catch (Exception ex) {
            log.error("Error committing deduction batch: ", ex);
        }
    }


    private List<NdasendaDeductionsBatchRequest> findBatchRequestsByDate(LocalDate fromDate, LocalDate toDate) {
        log.info("Find batches requestsfrom: {} to {}", fromDate, toDate);
        try {
            ResponseEntity<List<NdasendaDeductionsBatchRequest>> response = restTemplate.exchange(
                    ndasendaProps.getDeductionRequestsByDateRangeEndpoint(),
                    HttpMethod.GET,
                    new HttpEntity<>(getHttpHeaders()),
                    new ParameterizedTypeReference<List<NdasendaDeductionsBatchRequest>>() {
                    },
                    dateTimeFormatter.format(fromDate),
                    dateTimeFormatter.format(toDate),
                    ndasendaProps.getDeductionCode());
            final List<NdasendaDeductionsBatchRequest> responseBody = response.getBody();
            return responseBody;
        } catch (Exception ex) {
            log.error("", ex);
            return Collections.emptyList();
        }
    }


    private List<NdasendaDeductionsBatchRequest> findBatchResponsesByDate(LocalDate fromDate, LocalDate toDate) {
        log.info("Find batches from: {} to {}", fromDate, toDate);
        try {
            ResponseEntity<List<NdasendaDeductionsBatchRequest>> response = restTemplate.exchange(
                    ndasendaProps.getDeductionResponsesByDateRangeEndpoint(),
                    HttpMethod.GET,
                    new HttpEntity<>(getHttpHeaders()),
                    new ParameterizedTypeReference<List<NdasendaDeductionsBatchRequest>>() {
                    },
                    dateTimeFormatter.format(fromDate),
                    dateTimeFormatter.format(toDate),
                    ndasendaProps.getDeductionCode());
            final List<NdasendaDeductionsBatchRequest> responseBody = response.getBody();
            return responseBody;
        } catch (Exception ex) {
            log.error("", ex);
            return Collections.emptyList();
        }
    }

    public List<NdasendaDeductionsBatchRequest> findDeductionResponsesByBatchId(String batchId) {
        log.info("Find batch id: {}", batchId);
        try {
            ResponseEntity<List<NdasendaDeductionsBatchRequest>> response = restTemplate.exchange(ndasendaProps.getDeductionResponsesByBatchId(),
                    GET, new HttpEntity<>(getHttpHeaders()),
                    new ParameterizedTypeReference<List<NdasendaDeductionsBatchRequest>>() {
                    }, batchId);
            return response.getBody();
        } catch (Exception ex) {
            log.error("", ex);
            return Collections.emptyList();
        }
    }

    public NdasendaDeductionsBatchRequest findBatchById(String batchId) {
        log.info("Find batch id: {}", batchId);
        ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(ndasendaProps.getFindBatchEndpoint(),
                GET, new HttpEntity<>(getHttpHeaders()),
                NdasendaDeductionsBatchRequest.class,
                batchId);
        return response.getBody();
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
                                String.format("%09d", loan.getId()), response.getMessage());
                        notificationService.sendSms(loan.getMobileNumber(), text);

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
