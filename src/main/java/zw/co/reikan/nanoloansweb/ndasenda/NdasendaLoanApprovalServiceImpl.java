package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    @Override
    public SsbResponse process(LoanApprovalRequest loanRequest) {
        return null;
    }

    private String createOrAssignBatchNumber(LoanApprovalRequest loanRequest) {
        log.info("Creating and/or assigning batch number");
        return null;
    }

    private void batchApprovalRequest(LoanApprovalRequest request) {
        log.info("Requesting loan deduction");

        final String batchId = batchRepository.findByDeductionBatchStatus(DeductionBatchStatus.DRAFT)
                .map(NdasendaBatch::getBatchId)
                .orElse(null);

        boolean batchExists = batchId == null;

        final NdasendaDeductionsBatchRequest batch = NdasendaDeductionsBatchRequest.builder()
                .id(batchId)
                .deductions(List.of(fromLoanRequest(request)))
                .build();

        HttpEntity<NdasendaDeductionsBatchRequest> requestEntity = new HttpEntity<>(batch, getHttpHeaders());

        ResponseEntity<NdasendaDeductionsBatchRequest> response = restTemplate.exchange(ndasendaProps.getDeductionsEndpoint(),
                POST, requestEntity, NdasendaDeductionsBatchRequest.class);

        NdasendaDeductionsBatchRequest deductionsBatchResponse = response.getBody();


    }

    private NdasendaDeductionRequest fromLoanRequest(LoanApprovalRequest request) {
        return NdasendaDeductionRequest.builder()
                .amountInCents(toCents(request.getMonthlyInstallment()))
                .ecNumber(request.getEcnumber())
                .idNumber(request.getIdNumber())
                .payrollNumber(request.getPayrollNumber())
                .name(request.getName())
                .surname(request.getSurname())
                .totalAmountInCents(toCents(request.getTotalAmount()))
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
