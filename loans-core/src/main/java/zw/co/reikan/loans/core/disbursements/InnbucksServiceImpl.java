package zw.co.reikan.loans.core.disbursements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.loans.core.DisbursementRequest;
import zw.co.reikan.loans.core.DisbursementResponse;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.MsisdnUtil;
import zw.co.reikan.loans.core.loan.DisbursementStatus;
import zw.co.reikan.loans.core.loan.LoanDisbursementRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;

import static zw.co.reikan.loans.core.Utils.generateReference;

@Slf4j
@Service
public class InnbucksServiceImpl extends DisbursementService {

    private static final BigDecimal CENTS = new BigDecimal("100");
    private final InnbucksAuthService innbucksAuthService;
    private final RestTemplate restTemplate;
    private final InnbucksParameters parameters;

    public InnbucksServiceImpl(LoanRepository loanRepository, NotificationService notificationService,
                               LoanDisbursementRepository loanDisbursementRepository,
                               RestTemplate restTemplate, InnbucksParameters parameters,
                               InnbucksAuthService innbucksAuthService
    ) {
        super(loanRepository, notificationService, loanDisbursementRepository);
        this.innbucksAuthService = innbucksAuthService;
        this.restTemplate = restTemplate;
        this.parameters = parameters;
    }

    public DisbursementResponse disburseFunds(DisbursementRequest request) {
        final String uniqueTxnReference = generateReference(request.getMobileNumber());
        try {
            log.info("Processing loan disbursement: {}", request);

            InnbucksDepositRequest depositRequest = InnbucksDepositRequest.builder()
                    .amount(toCents(request.getAmount()))
                    .destinationMsisdn(MsisdnUtil.formatMsisdnInternational(request.getMobileNumber()))
                    .reference(uniqueTxnReference)
                    .narration(String.format("Ref: %s", request.getReference()))
                    .build();

            HttpEntity<InnbucksDepositRequest> requestEntity = new HttpEntity<>(depositRequest, getHttpHeaders(uniqueTxnReference));

            ResponseEntity<InnbucksDepositResponse> responseEntity = restTemplate.exchange(
                    parameters.getDepositEndpoint(),
                    HttpMethod.POST,
                    requestEntity,
                    InnbucksDepositResponse.class);

            final InnbucksDepositResponse depositResponse = responseEntity.getBody();

            final boolean success = depositResponse.getResponseCode() == 0;

            return DisbursementResponse.builder()
                    .status(success ? DisbursementStatus.SUCCESS : DisbursementStatus.FAILED)
                    .approvalCode(depositResponse.getAuthNumber())
                    .internalReference(depositResponse.getStan())
                    .message(depositResponse.getResponseMsg())
                    .build();

        } catch (Exception ex) {
            log.error("", ex);
            return DisbursementResponse.builder()
                    .status(DisbursementStatus.FAILED)
                    .internalReference(uniqueTxnReference)
                    .message(ex.getMessage())
                    .build();
        }
    }

    private HttpHeaders getHttpHeaders(String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(innbucksAuthService.getAccessToken());
        headers.add(InnbucksContants.X_API_KEY, parameters.getApiKey());
        headers.add(InnbucksContants.X_TRACE_ID, traceId);
        return headers;
    }

    private int toCents(BigDecimal amount) {
        return amount.multiply(CENTS).intValue();
    }

}
