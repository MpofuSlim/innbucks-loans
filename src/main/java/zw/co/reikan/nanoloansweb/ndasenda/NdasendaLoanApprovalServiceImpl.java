package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.nanoloansweb.loan.LoanApproval;
import zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus;
import zw.co.reikan.nanoloansweb.loan.LoanRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

@Slf4j
@RequiredArgsConstructor
public class NdasendaLoanApprovalServiceImpl implements LoanApprovalService {

    private static final BigDecimal CENTS = new BigDecimal("100");
    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final RestTemplate restTemplate;
    private final NdasendaAuthServiceImpl ndasendaAuthService;
    private final NdasendaParameters ndasendaProps;
    private final NdasendaBatchRepository batchRepository;

    private final LoanRepository loanRepository;

    private void processDeductionRequestResponse(NdasendaDeduction response) {

        log.info("Processing deduction response: {}", response);

        loanRepository.findById(Long.parseLong(response.getReference()))
                .ifPresent(loan -> {
                    loan.setLoanApprovalStatus(response.getStatus().getApprovalStatus());
                    loan.setDateApproved(LocalDateTime.now());
                    loan.setApprovalReference(response.getId());
                    loanRepository.save(loan);
                });
    }

    public void commit(String batchId) {
        log.info("Committing batch id: {}", batchId);
        ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(ndasendaProps.getCommitDeductionsEndpoint(),
                POST, new HttpEntity<>(getHttpHeaders()),
                NdasendaDeductionsBatchRequest.class,
                batchId);
    }

    public NdasendaDeductionsBatchRequest getBatch(String batchId) {

        log.info("Find batch id: {}", batchId);

        ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(ndasendaProps.getFindBatchEndpoint(),
                GET, new HttpEntity<>(getHttpHeaders()),
                NdasendaDeductionsBatchRequest.class,
                batchId);
        return response.getBody();
    }

    public LoanApprovalResponse process(LoanApprovalRequest request) {

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

        ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(ndasendaProps.getDeductionsRequestsEndpoint(),
                POST, requestEntity, NdasendaDeductionsBatchRequest.class);

        NdasendaDeductionsBatchRequest deductionsBatchResponse = response.getBody();

        return LoanApprovalResponse.builder()
                .status(LoanApprovalStatus.PROCESSING)
                .batchNumber(deductionsBatchResponse.getId())
                .build();
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
