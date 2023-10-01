package zw.co.reikan.nanoloansweb.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.nanoloansweb.DisbursementRequest;
import zw.co.reikan.nanoloansweb.DisbursementResponse;
import zw.co.reikan.nanoloansweb.DisbursementService;
import zw.co.reikan.nanoloansweb.MsisdnUtil;
import zw.co.reikan.nanoloansweb.loan.DisbursementStatus;

import java.math.BigDecimal;

import static zw.co.reikan.nanoloansweb.Utils.generateReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class InnbucksServiceImpl implements DisbursementService {

    private static final BigDecimal CENTS = new BigDecimal("100");
    private final InnbucksAuthService innbucksAuthService;
    private final RestTemplate restTemplate;
    private final InnbucksParameters parameters;

    @Override
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
