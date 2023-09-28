package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus;
import zw.co.reikan.nanoloansweb.loan.LoanRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

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


    public LoanApprovalResponse requestApproval(LoanApprovalRequest request) {

        log.info("Requesting loan deduction");

        final NdasendaDeduction deductionRequest = fromLoanRequest(request);

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
                .build();
    }

    public void processDeductionResponses(LocalDate today) {
        log.info("Process deduction responses for: {}", today);
        findBatchResponsesByDate(today, today).parallelStream()
                .map(NdasendaDeductionsBatchRequest::getId)
                .map(this::findDeductionResponsesByBatchId)
                .flatMap(responses -> responses.stream())
                .flatMap(batch -> batch.getDeductions().stream())
                .forEach(this::processDeductionRequestResponse);
    }

    public void commitDeductionRequestsUntilNow() {
        log.info("Committing batch id");
        try {
            ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(ndasendaProps.getCommitDeductionsEndpoint(),
                    POST, new HttpEntity<>(getHttpHeaders()),
                    NdasendaDeductionsBatchRequest.class,
                    ndasendaProps.getDeductionCode());
        } catch (ResourceAccessException rae) {
            log.warn("No pending batch to commit");
        } catch (Exception ex) {
            log.error("Error committing deduction batch: ", ex);
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

    private List<NdasendaDeductionsBatchRequest> findDeductionResponsesByBatchId(String batchId) {
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

    private NdasendaDeductionsBatchRequest findBatchById(String batchId) {
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
                log.warn("Invalid referece: {}", response.getReference());
                return;
            }

            loanRepository.findById(id)
                    .ifPresent(loan -> {
                        loan.setLoanApprovalStatus(response.getStatus().getApprovalStatus());
                        loan.setDateApproved(LocalDateTime.now());
                        loan.setApprovalReference(response.getId());
                        loanRepository.save(loan);
                    });
        } catch (Exception ex) {
            log.error("", ex);
        }
    }

    private NdasendaDeduction fromLoanRequest(LoanApprovalRequest request) {
        return NdasendaDeduction.builder()
                .amountInCents(toCents(request.getMonthlyInstallment()))
                .ecNumber(request.getEcnumber())
                .idNumber(request.getIdNumber())
                .startDate(formatDate(request.getStartDate()))
                .endDate(formatDate(request.getEndDate()))
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
