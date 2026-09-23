package zw.co.reikan.loans.core.disbursements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.loans.core.DisbursementRequest;
import zw.co.reikan.loans.core.DisbursementResponse;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.Utils;
import zw.co.reikan.loans.core.loan.*;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static zw.co.reikan.loans.core.MsisdnUtil.formatMsisdnInternational;
import static zw.co.reikan.loans.core.Utils.*;

@Slf4j
@Service
public class InnbucksServiceImpl extends DisbursementService {

    public static final String MONTHLY = "MONTHLY";
    public static final String COUNTRY_CODE = "263";
    private static final BigDecimal CENTS = new BigDecimal("100");
    private final InnbucksAuthService innbucksAuthService;
    private final RestTemplate restTemplate;
    private final InnbucksParameters parameters;

    public InnbucksServiceImpl(LoanRepository loanRepository, NotificationService notificationService,
                               LoanDisbursementRepository loanDisbursementRepository,
                               RestTemplate restTemplate, InnbucksParameters parameters,
                               InnbucksAuthService innbucksAuthService,
                               PlatformTransactionManager transactionManager
    ) {
        super(loanRepository, notificationService, loanDisbursementRepository, transactionManager);
        this.innbucksAuthService = innbucksAuthService;
        this.restTemplate = restTemplate;
        this.parameters = parameters;
    }

    public LoanAccountCreationResponse createLoanAccount(Loan loan) {
        log.info("Processing loan account creation");

        // Pass through the loan's real values; when a field is absent it is left
        // null (and omitted from the outbound JSON) rather than filled with a
        // placeholder default. currency/repaymentFrequency remain fixed
        // integration constants; product is per-deployment config.
        String businessLine = loan.getLineOfBusiness() == null ? null : loan.getLineOfBusiness().getDescription();
        String loanPurpose = loan.getLoanPurpose() == null ? null : loan.getLoanPurpose().getDescription();
        BigDecimal grossSalary = (loan.getEmploymentDetail() == null || loan.getEmploymentDetail().getGrossSalary() == null)
                ? null : loan.getEmploymentDetail().getGrossSalary();

        DisbursementType disbursementType = loan.getMerchant().getDisbursementType();

        LoanAccountCreationRequest.LoanAccountCreationRequestBuilder builder = LoanAccountCreationRequest.builder()
                .firstName(loan.getFirstName())
                .lastName(loan.getLastName())
                .idNumber(loan.getNationalIdNumber())
                .address(loan.getAddress().toString())
                .dateOfBirth(loan.getDateOfBirth().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")))
                .currency("USD")
                .amount(toCents(loan.getPrincipal()))
                .maritalStatus(loan.getMaritalStatus() == null ? null : loan.getMaritalStatus().getCode())
                .businessLine(businessLine)
                .loanPurpose(loanPurpose)
                .grossSalary(grossSalary == null ? null : toCents(grossSalary))
                .msisdn(formatMsisdnInternational(loan.getMobileNumber()))
                .numberOfDependents(loan.getNumberOfDependencies())
                .participantReference(loan.getReference())
                .placeOfBirth(loan.getPlaceOfBirth())
                .product(parameters.getLoanProduct())
                .tenureInMonths(loan.getTenor())
                .type(disbursementType.getLoanType().name())
                .repaymentFrequency(MONTHLY);

        if (DisbursementType.MERCHANT_MOBILE_WALLET == disbursementType) {
            log.info("Setting disbursement account: {}", loan.getMerchant().getAccountNumber());
            builder.settlementAccount(loan.getMerchant().getAccountNumber());
        }

        NextOfKin nextOfKin = loan.getNextOfKin();
        if (nextOfKin != null) {
            builder.nextOfKinIdNumber(trimToNull(nextOfKin.getNationalId()))
                    .nextOfKinFullName(nextOfKin.getLastName() == null ? nextOfKin.getFirstName() : nextOfKin.getFirstName() + " " + nextOfKin.getLastName())
                    .nextOfKinAddress(nextOfKin.getAddress().toString())
                    .nextOfKinMsisdn(COUNTRY_CODE + right(nextOfKin.getMobileNumber(), 9))
                    .nextOfKinRelationship(nextOfKin.getRelationship().getDisplayName());
        }

        EmploymentDetail employmentDetail = loan.getEmploymentDetail();

        // Only send employment fields we actually have; no employment detail means
        // employer/employerNumber/employmentStartDate are left unset (omitted),
        // not defaulted to NOTSPECIFIED / the EC number / today's date.
        if (employmentDetail != null) {
            builder.employerNumber(trimToNull(employmentDetail.getEmployeeNumber()))
                    .employer(trimToNull(employmentDetail.getEmployerName()))
                    .employmentStartDate(employmentDetail.getEmploymentStartDate() == null
                            ? null
                            : employmentDetail.getEmploymentStartDate().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
        }

        LoanAccountCreationRequest requestBody = builder.build();
        try {
            return executeCreateLoanAccount(loan, requestBody);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.warn("Token expired during loan account creation. Refreshing token and retrying...");
                // Refresh token and retry
                innbucksAuthService.refreshToken();
                return executeCreateLoanAccount(loan, requestBody);
            }
            throw e;
        }
    }

    private LoanAccountCreationResponse executeCreateLoanAccount(Loan loan, LoanAccountCreationRequest requestBody) {
        HttpEntity<LoanAccountCreationRequest> requestEntity = new HttpEntity<>(requestBody,
                getHttpHeaders(loan.getReference()));

        ResponseEntity<String> responseEntity = restTemplate.exchange(
                parameters.getCreateLoanAccountEndpoint(),
                HttpMethod.POST,
                requestEntity,
                String.class);

        log.info("Account creation response: {}", responseEntity.getBody());

        // Any 2xx is a created application. This used to compare against 200
        // only, so a 201 Created — a normal answer to a POST that creates —
        // would have marked a loan InnBucks had just booked as FAILED.
        boolean created = responseEntity.getStatusCode().is2xxSuccessful();
        return LoanAccountCreationResponse.builder()
                .reference(loan.getReference())
                .success(created)
                .message(created ? null : "InnBucks answered HTTP " + responseEntity.getStatusCode().value())
                .build();
    }


    /**
     * One deposit under the caller's stable reference. Never re-sent, except the single
     * replay after a 401 (an unauthenticated request was not processed), which carries the
     * SAME reference. Classifies rather than throws:
     * <ul>
     *   <li>SUCCESS — a 2xx with responseCode 0;</li>
     *   <li>FAILED — only when nothing can have been paid: a 2xx with responseCode != 0, a
     *       4xx carrying InnBucks' own refusal, or a failure before the request left;</li>
     *   <li>UNKNOWN — everything else (timeouts, resets, 5xx, unreadable answers), because
     *       the money may have moved. This used to be FAILED under a fresh reference per
     *       call, so a payout that landed but timed out was simply paid again.</li>
     * </ul>
     */
    public DisbursementResponse disburseFunds(DisbursementRequest request) {
        final String reference = request.getTransactionReference();
        final InnbucksDepositRequest depositRequest;
        final HttpHeaders headers;
        try {
            depositRequest = buildDepositRequest(request, reference);
            headers = getHttpHeaders(reference);
        } catch (RuntimeException ex) {
            // Nothing has been written to InnBucks yet, so nothing can have been paid.
            log.error("Deposit {} not sent", reference, ex);
            return disbursementFailed(reference, "Not sent to InnBucks: " + describe(ex));
        }

        try {
            log.info("Processing loan disbursement {}: {}", reference, request);
            try {
                return executeDisburseFunds(depositRequest, headers, reference);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() != HttpStatus.UNAUTHORIZED) {
                    throw e;
                }
                log.warn("Token expired during funds disbursement. Refreshing token and replaying {} once...", reference);
                innbucksAuthService.refreshToken();
                return executeDisburseFunds(depositRequest, getHttpHeaders(reference), reference);
            }
        } catch (HttpClientErrorException e) {
            return classifyClientError(e, reference);
        } catch (Exception ex) {
            log.error("Deposit {} outcome unknown", reference, ex);
            return disbursementUnknown(reference, describe(ex));
        }
    }

    private InnbucksDepositRequest buildDepositRequest(DisbursementRequest request, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("a deposit needs the caller's stable transaction reference");
        }
        InnbucksDepositRequest.InnbucksDepositRequestBuilder builder = InnbucksDepositRequest.builder()
                .amount(toCents(request.getAmount()))
                .reference(reference)
                .narration(String.format("Ref: %s", request.getReference()));

        // A missing destination is never guessed as the customer: a merchant (consumer-finance)
        // loan paid to the customer is money sent to the wrong party.
        if (request.getDisbursementType() == DisbursementType.CUSTOMER_MOBILE_WALLET) {
            log.info("Disbursing to customer:{}", request);
            builder.destinationMsisdn(formatMsisdnInternational(request.getMobileNumber()));
        } else if (request.getDisbursementType() == DisbursementType.MERCHANT_MOBILE_WALLET
                && request.getAccountNumber() != null && !request.getAccountNumber().isBlank()) {
            log.info("Disbursing to merchant: {}", request);
            builder.destinationAccount(request.getAccountNumber());
        } else {
            throw new IllegalArgumentException("no disbursement destination (type "
                    + request.getDisbursementType() + ")");
        }
        return builder.build();
    }

    private DisbursementResponse executeDisburseFunds(InnbucksDepositRequest depositRequest, HttpHeaders headers,
                                                      String reference) {
        HttpEntity<InnbucksDepositRequest> requestEntity = new HttpEntity<>(depositRequest, headers);

        ResponseEntity<InnbucksDepositResponse> responseEntity = restTemplate.exchange(
                parameters.getDepositEndpoint(),
                HttpMethod.POST,
                requestEntity,
                InnbucksDepositResponse.class);

        final InnbucksDepositResponse depositResponse = responseEntity.getBody();

        if (!responseEntity.getStatusCode().is2xxSuccessful()
                || depositResponse == null || depositResponse.getResponseCode() == null) {
            // An answer we cannot read says nothing about whether the money moved.
            return disbursementUnknown(reference, "InnBucks answered HTTP "
                    + responseEntity.getStatusCode().value() + " without a response code");
        }
        if (depositResponse.getResponseCode() != 0) {
            return disbursementFailed(reference, "responseCode " + depositResponse.getResponseCode()
                    + " " + depositResponse.getResponseMsg());
        }

        return DisbursementResponse.builder()
                .status(DisbursementStatus.SUCCESS)
                .approvalCode(depositResponse.getAuthNumber())
                .internalReference(depositResponse.getStan())
                .message(depositResponse.getResponseMsg() == null ? null : Utils.left(depositResponse.getResponseMsg(), 200))
                .build();
    }

    /**
     * A 4xx proves nothing was paid only when it is InnBucks' own refusal: its deposit
     * envelope with a non-zero responseCode. A body we cannot read (a proxy or WAF page) proves
     * nothing; neither does a 408 (it stopped reading) or a 409 (most plausibly this reference
     * already exists — i.e. an earlier attempt landed).
     */
    private DisbursementResponse classifyClientError(HttpClientErrorException e, String reference) {
        int status = e.getStatusCode().value();
        InnbucksDepositResponse body = readDepositBody(e);
        if (status != 408 && status != 409
                && body != null && body.getResponseCode() != null && body.getResponseCode() != 0) {
            log.warn("Deposit {} refused by InnBucks: HTTP {} {}", reference, status, e.getResponseBodyAsString());
            return disbursementFailed(reference, "HTTP " + status + " responseCode " + body.getResponseCode()
                    + " " + body.getResponseMsg());
        }
        log.error("Deposit {} answered HTTP {} with no refusal we can rely on: {}",
                reference, status, e.getResponseBodyAsString());
        return disbursementUnknown(reference, describe(e));
    }

    private static InnbucksDepositResponse readDepositBody(HttpClientErrorException e) {
        try {
            return e.getResponseBodyAs(InnbucksDepositResponse.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static DisbursementResponse disbursementFailed(String reference, String message) {
        return DisbursementResponse.builder()
                .status(DisbursementStatus.FAILED)
                .internalReference(reference)
                .message(Utils.left(message, 200))
                .build();
    }

    private static DisbursementResponse disbursementUnknown(String reference, String message) {
        return DisbursementResponse.builder()
                .status(DisbursementStatus.UNKNOWN)
                .internalReference(reference)
                .message(Utils.left(message, 200))
                .build();
    }

    private static String describe(Exception ex) {
        if (ex instanceof RestClientResponseException http) {
            String body = http.getResponseBodyAsString();
            return "HTTP " + http.getStatusCode().value() + (body.isBlank() ? "" : " " + body.strip());
        }
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private HttpHeaders getHttpHeaders(String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(innbucksAuthService.getAccessToken());
        headers.add(InnbucksContants.X_API_KEY, parameters.getApiKey());
        headers.add(InnbucksContants.X_TRACE_ID, traceId);
        return headers;
    }

    /**
     * Surrounding whitespace only. The collection sends identifiers verbatim
     * ({@code 63-7654321A63}, {@code EMP-001}); the old trimSpecialCharacters
     * stripped every non-word character, which also glued multi-word employer
     * names together ("Mutare City Council" -> "MutareCityCouncil").
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int toCents(BigDecimal amount) {
        return amount.multiply(CENTS).intValue();
    }

    @Override
    public LoanDisbursementStatusResponse checkLoanDisbursementStatus(Loan loan) {
        log.info("Checking loan disbursement status for loan: {} with reference: {}", loan.getId(), loan.getReference());

        try {
            return executeCheckLoanDisbursementStatus(loan);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.warn("Token expired during loan status check for loan: {}. Refreshing token and retrying...", loan.getId());
                // Refresh token and retry
                innbucksAuthService.refreshToken();
                return executeCheckLoanDisbursementStatus(loan);
            } else if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("Loan not found in Innbucks system for loan: {}", loan.getId());
                return buildErrorResponse(loan, "Loan not found in Innbucks system: " + e.getMessage());
            } else {
                log.error("HTTP error checking loan disbursement status for loan: {}, status: {}", 
                        loan.getId(), e.getStatusCode(), e);
                return buildErrorResponse(loan, "HTTP error: " + e.getStatusCode() + " - " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error checking loan disbursement status for loan: {}", loan.getId(), e);
            return buildErrorResponse(loan, "Unexpected error: " + e.getMessage());
        }
    }

    private LoanDisbursementStatusResponse executeCheckLoanDisbursementStatus(Loan loan) {
        String url = parameters.getLoanInquiryEndpoint().replace("{participantReference}", loan.getReference());
        log.debug("Checking loan disbursement status at URL: {} for loan: {}", url, loan.getId());

        HttpEntity<Void> requestEntity = new HttpEntity<>(getHttpHeaders(loan.getReference()));

        ResponseEntity<LoanDisbursementStatusResponse> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.GET,
                requestEntity,
                LoanDisbursementStatusResponse.class);

        log.debug("Received HTTP status: {} for loan: {}", responseEntity.getStatusCode(), loan.getId());

        LoanDisbursementStatusResponse response = responseEntity.getBody();

        if (response == null) {
            log.warn("Received null response body from Innbucks API for loan: {}", loan.getId());
            return buildErrorResponse(loan, "Null response received from Innbucks API");
        }

        log.info("Loan disbursement status response for loan {}: responseCode={}, description={}, status={}",
                loan.getId(), response.getResponseCode(), response.getResponseDescription(),
                response.getAdditionalData() != null && response.getAdditionalData().getLoanDetails() != null ? 
                        response.getAdditionalData().getLoanDetails().getStatus() : "N/A");

        response.setSuccess(responseEntity.getStatusCode().is2xxSuccessful());

        // Set the loan status based on the response
        if (response.isSuccess() && response.isApproved()) {
            response.setStatus(response.determineLoanStatus());
            log.info("Determined loan status for loan {}: {}", loan.getId(), response.getStatus());
        } else {
            response.setStatus(LoanDisbursementStatus.PENDING);
            log.info("Setting loan status to PENDING for loan {}", loan.getId());
        }

        return response;
    }

    private LoanDisbursementStatusResponse buildErrorResponse(Loan loan, String errorMessage) {
        return LoanDisbursementStatusResponse.builder()
                .responseCode("999")
                .responseDescription("Error checking loan status: " + errorMessage)
                .reference(loan.getReference())
                .participantReference(loan.getReference())
                .status(LoanDisbursementStatus.PENDING)
                .statusMessage(errorMessage)
                .success(false)
                .build();
    }
}
