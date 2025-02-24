package zw.co.reikan.loans.core.disbursements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
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
    public static final String SSBUSD = "SSBUSD";
    public static final String COUNTRY_CODE = "263";
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

    public LoanAccountCreationResponse createLoanAccount(Loan loan) {
        log.info("Processing loan account creation");

        String businessLine = loan.getLineOfBusiness() != null ?
                loan.getLineOfBusiness().getDescription() : LineOfBusiness.SERVICES.getDescription();

        String loanPurpose = loan.getLoanPurpose() != null ? loan.getLoanPurpose().getDescription() : LoanPurpose.PERSONAL_USE.getDescription();

        DisbursementType disbursementType = loan.getMerchant().getDisbursementType();

        LoanAccountCreationRequest.LoanAccountCreationRequestBuilder builder = LoanAccountCreationRequest.builder()
                .firstName(loan.getFirstName())
                .lastName(loan.getLastName())
                .idNumber(loan.getNationalIdNumber())
                .address(loan.getAddress().toString())
                .dateOfBirth(loan.getDateOfBirth().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")))
                .currency("USD")
                .amount(toCents(loan.getPrincipal()))
                .maritalStatus(loan.getMaritalStatus() == null ? MaritalStatus.SINGLE.getCode() : loan.getMaritalStatus().getCode())
                .businessLine(businessLine)
                .loanPurpose(loanPurpose)
                .grossSalary(toCents(loan.getEmploymentDetail() == null ? BigDecimal.ONE : loan.getEmploymentDetail().getGrossSalary()))
                .msisdn(formatMsisdnInternational(loan.getMobileNumber()))
                .numberOfDependents(loan.getNumberOfDependencies())
                .numberOfChildren(loan.getNumberOfDependencies())
                .participantReference(loan.getReference())
                .placeOfBirth(loan.getPlaceOfBirth() == null ? "UNKNOWN" : loan.getPlaceOfBirth())
                .product(SSBUSD)
                .tenureInMonths(loan.getTenor())
                .type(disbursementType.getLoanType().name())
                .repaymentFrequency(MONTHLY);

        if (DisbursementType.MERCHANT_MOBILE_WALLET == disbursementType) {
            log.info("Setting disbursement account: {}", loan.getMerchant().getAccountNumber());
            builder.settlementAccount(loan.getMerchant().getAccountNumber());
        }

        NextOfKin nextOfKin = loan.getNextOfKin();
        if (nextOfKin != null) {
            builder.nextOfKinIdNumber(trimSpecialCharacters(nextOfKin.getNationalId()))
                    .nextOfKinFullName(nextOfKin.getLastName() == null ? nextOfKin.getFirstName() : nextOfKin.getFirstName() + " " + nextOfKin.getLastName())
                    .nextOfKinAddress(nextOfKin.getAddress().toString())
                    .nextOfKinMsisdn(COUNTRY_CODE + right(nextOfKin.getMobileNumber(), 9))
                    .nextOfKinRelationship(nextOfKin.getRelationship().getDisplayName());
        }

        EmploymentDetail employmentDetail = loan.getEmploymentDetail();

        if (employmentDetail != null) {
            LocalDate employmentStartDate = employmentDetail.getEmploymentStartDate() == null ?
                    LocalDate.now() :
                    employmentDetail.getEmploymentStartDate();

            String employeeNumber = employmentDetail.getEmployeeNumber() == null ? loan.getEcNumber() : employmentDetail.getEmployeeNumber();

            builder.employerNumber(trimSpecialCharacters(employeeNumber))
                    .employer(trimSpecialCharacters(employmentDetail.getEmployerName()))
                    .employmentStartDate(employmentStartDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
        } else {
            builder.employerNumber(trimSpecialCharacters(loan.getEcNumber()))
                    .employer("NOTSPECIFIED")
                    .employmentStartDate(LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
        }


        HttpEntity<LoanAccountCreationRequest> requestEntity = new HttpEntity<>(builder.build(),
                getHttpHeaders(loan.getReference()));

        ResponseEntity<String> responseEntity = restTemplate.exchange(
                parameters.getCreateLoanAccountEndpoint(),
                HttpMethod.POST,
                requestEntity,
                String.class);

        log.info("Account creation response: {}", responseEntity.getBody());

        return LoanAccountCreationResponse.builder()
                .reference(loan.getReference())
                .success(responseEntity.getStatusCode() == HttpStatus.OK)
                .build();
    }


    public DisbursementResponse disburseFunds(DisbursementRequest request) {
        final String uniqueTxnReference = generateReference(request.getMobileNumber());
        try {
            log.info("Processing loan disbursement: {}", request);

            InnbucksDepositRequest.InnbucksDepositRequestBuilder builder = InnbucksDepositRequest.builder()
                    .amount(toCents(request.getAmount()))
                    .reference(uniqueTxnReference)
                    .narration(String.format("Ref: %s", request.getReference()));

            if (request.getDisbursementType() == null || request.getDisbursementType() == DisbursementType.CUSTOMER_MOBILE_WALLET) {
                log.info("Disbursing to customer:{}", request);
                builder.destinationMsisdn(formatMsisdnInternational(request.getMobileNumber()));
            } else {
                log.info("Disbursing to merchant: {}", request);
                builder.destinationAccount(request.getAccountNumber());
            }

            HttpEntity<InnbucksDepositRequest> requestEntity = new HttpEntity<>(builder.build(), getHttpHeaders(uniqueTxnReference));

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
                    .message(Utils.left(depositResponse.getResponseMsg(), 200))
                    .build();

        } catch (Exception ex) {
            log.error("Error disbursing funds", ex);
            return DisbursementResponse.builder()
                    .status(DisbursementStatus.FAILED)
                    .internalReference(uniqueTxnReference)
                    .message(Utils.left(ex.getMessage(), 200))
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
