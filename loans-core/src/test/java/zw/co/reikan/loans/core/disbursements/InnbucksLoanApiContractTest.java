package zw.co.reikan.loans.core.disbursements;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.loans.core.loan.Address;
import zw.co.reikan.loans.core.loan.DisbursementType;
import zw.co.reikan.loans.core.loan.EmploymentDetail;
import zw.co.reikan.loans.core.loan.LineOfBusiness;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanDisbursementRepository;
import zw.co.reikan.loans.core.loan.LoanPurpose;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.loan.MaritalStatus;
import zw.co.reikan.loans.core.loan.NextOfKin;
import zw.co.reikan.loans.core.loan.RelationshipType;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Contract test: pins the InnBucks loan API calls to the IT team's
 * "InnBucks Loans" Postman collection — {@code POST /auth/third-party},
 * {@code POST /bank/api/loan/apply/pre-approved} and
 * {@code GET /bank/api/loan/inquiry/{participantReference}}.
 *
 * <p><b>What is and is not transcribed.</b> The REQUEST side (paths, headers,
 * field names, date format, cents) is transcribed from that collection, so a
 * drift in what we SEND fails here. The collection ships NO example responses:
 * the login's {@code accessToken} is the shape the notification rail already
 * observes on the same endpoint, the apply stub's body is deliberately opaque
 * (only the status is read), and the inquiry body is the repo's existing model
 * — not an observed response. Replace those stubs with recorded responses once
 * InnBucks supplies them.
 *
 * <p>Pure JUnit + WireMock, no Spring context — so {@link InnbucksAuthService}'s
 * {@code @Cacheable} is inert here and every call logs in afresh.
 */
class InnbucksLoanApiContractTest {

    private static final String API_KEY = "test-api-key";
    private static final String LOGIN = "/auth/third-party";
    private static final String APPLY = "/bank/api/loan/apply/pre-approved";
    private static final String INQUIRY = "/bank/api/loan/inquiry/";
    private static final String TOKEN = "tok-abc";

    private static WireMockServer wireMock;
    private InnbucksServiceImpl service;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void setup() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(LOGIN))
                .willReturn(okJson("{\"accessToken\":\"" + TOKEN + "\"}")));
        String base = "http://localhost:" + wireMock.port();
        service = serviceAt(base, base);
    }

    /** Login against {@code authBase}; apply + inquiry against {@code loanBase}. */
    private static InnbucksServiceImpl serviceAt(String authBase, String loanBase) {
        InnbucksParameters params = new InnbucksParameters();
        params.setApiKey(API_KEY);
        params.setUsername("test-user");
        params.setPassword("test-pass");
        params.setAuthEndpoint(authBase + LOGIN);
        params.setCreateLoanAccountEndpoint(loanBase + APPLY);
        params.setLoanInquiryEndpoint(loanBase + INQUIRY + "{participantReference}");
        params.setLoanProduct("NANOUS");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(2000));
        RestTemplate restTemplate = new RestTemplate(factory);

        InnbucksAuthService auth = new InnbucksAuthService(restTemplate, params);
        return new InnbucksServiceImpl(mock(LoanRepository.class), mock(NotificationService.class),
                mock(LoanDisbursementRepository.class), restTemplate, params, auth);
    }

    /** The collection's sample applicant, as a loan this system would hold. */
    private static Loan collectionLoan(DisbursementType disbursementType) {
        Address home = new Address();
        home.setStreet("123 Samora Machel Ave");
        home.setCity("Harare");

        NextOfKin kin = new NextOfKin();
        kin.setFirstName("Jane");
        kin.setLastName("Mufambanaayo");
        kin.setNationalId("63-7654321A63");
        kin.setMobileNumber("0772321321");
        kin.setAddress(home);
        kin.setRelationship(RelationshipType.SPOUSE);

        EmploymentDetail job = new EmploymentDetail();
        job.setEmployerName("Mutare City Council");
        job.setEmployeeNumber("EMP-001");
        job.setEmploymentStartDate(LocalDate.of(2022, 1, 1));
        job.setGrossSalary(new BigDecimal("1500.00"));

        Merchant merchant = Merchant.builder()
                .disbursementType(disbursementType)
                .accountNumber("123456789")
                .build();

        Loan loan = Loan.builder()
                .firstName("James")
                .lastName("Mufambanaayo")
                .nationalIdNumber("63-1234567A63")
                .dateOfBirth(LocalDate.of(1990, 5, 14))
                .principal(new BigDecimal("500.00"))
                .tenor(6)
                .maritalStatus(MaritalStatus.MARRIED)
                .mobileNumber("0772123123")
                .numberOfDependencies(2)
                .placeOfBirth("Harare")
                .address(home)
                .nextOfKin(kin)
                .employmentDetail(job)
                .merchant(merchant)
                .lineOfBusiness(LineOfBusiness.values()[0])
                .loanPurpose(LoanPurpose.values()[0])
                .build();
        loan.setId(42L);
        return loan;
    }

    @Test
    @DisplayName("login: POST /auth/third-party with X-Api-Key and username/password; token rides as bearer")
    void loginFollowsTheCollection() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{}")));

        service.createLoanAccount(collectionLoan(DisbursementType.CUSTOMER_MOBILE_WALLET));

        wireMock.verify(postRequestedFor(urlEqualTo(LOGIN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withRequestBody(matchingJsonPath("$.username", equalTo("test-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("test-pass"))));
        wireMock.verify(postRequestedFor(urlEqualTo(APPLY))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    @DisplayName("apply: every field the collection sends, in its names, date format and cents")
    void applyBodyMatchesTheCollection() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{}")));

        LoanAccountCreationResponse response =
                service.createLoanAccount(collectionLoan(DisbursementType.CUSTOMER_MOBILE_WALLET));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getReference()).isEqualTo("000000042");
        wireMock.verify(postRequestedFor(urlEqualTo(APPLY))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("X-Trace-Id", equalTo("000000042"))
                .withRequestBody(matchingJsonPath("$.firstName", equalTo("James")))
                .withRequestBody(matchingJsonPath("$.lastName", equalTo("Mufambanaayo")))
                .withRequestBody(matchingJsonPath("$.idNumber", equalTo("63-1234567A63")))
                .withRequestBody(matchingJsonPath("$.address", equalTo("123 Samora Machel Ave,Harare")))
                // Pre-approved takes dd-MM-yyyy ("14-05-1990"); the collection's
                // localhost "direct" call uses ISO — do not copy that one.
                .withRequestBody(matchingJsonPath("$.dateOfBirth", equalTo("14-05-1990")))
                // $500.00 -> 50000 cents, as in the collection.
                .withRequestBody(matchingJsonPath("$.amount", equalTo("50000")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("USD")))
                .withRequestBody(matchingJsonPath("$.tenureInMonths", equalTo("6")))
                .withRequestBody(matchingJsonPath("$.repaymentFrequency", equalTo("MONTHLY")))
                .withRequestBody(matchingJsonPath("$.product", equalTo("NANOUS")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("PERSONAL")))
                .withRequestBody(matchingJsonPath("$.maritalStatus", equalTo("M")))
                .withRequestBody(matchingJsonPath("$.msisdn", equalTo("263772123123")))
                .withRequestBody(matchingJsonPath("$.participantReference", equalTo("000000042")))
                .withRequestBody(matchingJsonPath("$.numberOfDependents", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.nextOfKinIdNumber", equalTo("63-7654321A63")))
                .withRequestBody(matchingJsonPath("$.nextOfKinFullName", equalTo("Jane Mufambanaayo")))
                .withRequestBody(matchingJsonPath("$.nextOfKinMsisdn", equalTo("263772321321")))
                .withRequestBody(matchingJsonPath("$.nextOfKinRelationship", equalTo("Spouse")))
                .withRequestBody(matchingJsonPath("$.placeOfBirth", equalTo("Harare")))
                .withRequestBody(matchingJsonPath("$.employer", equalTo("Mutare City Council")))
                .withRequestBody(matchingJsonPath("$.employerNumber", equalTo("EMP-001")))
                .withRequestBody(matchingJsonPath("$.employmentStartDate", equalTo("01-01-2022")))
                .withRequestBody(matchingJsonPath("$.grossSalary", equalTo("150000")))
                .withRequestBody(matchingJsonPath("$.businessLine"))
                .withRequestBody(matchingJsonPath("$.loanPurpose")));
    }

    @Test
    @DisplayName("apply: sends nothing the collection does not — no numberOfChildren, no settlement for a customer wallet")
    void applySendsNoFieldOutsideTheCollection() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{}")));

        service.createLoanAccount(collectionLoan(DisbursementType.CUSTOMER_MOBILE_WALLET));

        wireMock.verify(postRequestedFor(urlEqualTo(APPLY))
                .withRequestBody(notMatching(".*numberOfChildren.*"))
                .withRequestBody(notMatching(".*settlementAccount.*")));
    }

    @Test
    @DisplayName("apply: a merchant-wallet loan is CONSUMER_FINANCE and names the merchant's settlement account")
    void merchantWalletCarriesTheSettlementAccount() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{}")));

        service.createLoanAccount(collectionLoan(DisbursementType.MERCHANT_MOBILE_WALLET));

        wireMock.verify(postRequestedFor(urlEqualTo(APPLY))
                .withRequestBody(matchingJsonPath("$.type", equalTo("CONSUMER_FINANCE")))
                .withRequestBody(matchingJsonPath("$.settlementAccount", equalTo("123456789"))));
    }

    @Test
    @DisplayName("apply: a 401 re-authenticates and replays exactly once")
    void applyReplaysOnceAfter401() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).inScenario("expired")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("refreshed"));
        wireMock.stubFor(post(urlEqualTo(APPLY)).inScenario("expired")
                .whenScenarioStateIs("refreshed")
                .willReturn(okJson("{}")));

        LoanAccountCreationResponse response =
                service.createLoanAccount(collectionLoan(DisbursementType.CUSTOMER_MOBILE_WALLET));

        assertThat(response.isSuccess()).isTrue();
        wireMock.verify(2, postRequestedFor(urlEqualTo(APPLY)));
    }

    @Test
    @DisplayName("apply: a 400 is raised and NOT retried")
    void applyRejectionIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> service.createLoanAccount(collectionLoan(DisbursementType.CUSTOMER_MOBILE_WALLET)))
                .isInstanceOf(HttpClientErrorException.class);
        wireMock.verify(1, postRequestedFor(urlEqualTo(APPLY)));
    }

    @Test
    @DisplayName("apply: a 5xx is raised and NOT retried — a write that may have landed is never re-sent")
    void applyServerErrorIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> service.createLoanAccount(collectionLoan(DisbursementType.CUSTOMER_MOBILE_WALLET)))
                .isInstanceOf(HttpServerErrorException.class);
        wireMock.verify(1, postRequestedFor(urlEqualTo(APPLY)));
    }

    @Test
    @DisplayName("apply: connect-refused surfaces as an exception rather than a false success")
    void applyConnectRefused() {
        // Login is on the live WireMock; only the loan URLs point at a closed port.
        InnbucksServiceImpl refused = serviceAt("http://localhost:" + wireMock.port(), "http://localhost:1");

        assertThatThrownBy(() -> refused.createLoanAccount(collectionLoan(DisbursementType.CUSTOMER_MOBILE_WALLET)))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("inquiry: GET /bank/api/loan/inquiry/{participantReference} with bearer + X-Api-Key")
    void inquiryFollowsTheCollection() {
        // Body is the repo's existing model, NOT an observed response — see class doc.
        wireMock.stubFor(get(urlEqualTo(INQUIRY + "000000042")).willReturn(okJson("""
                {"responseCode":"000","responseDescription":"Approved or completed successfully",
                 "additionalData":{"loanDetails":{"status":"SETTLED","participantReference":"000000042"}}}
                """)));

        LoanDisbursementStatusResponse status =
                service.checkLoanDisbursementStatus(collectionLoan(DisbursementType.CUSTOMER_MOBILE_WALLET));

        assertThat(status.isSuccess()).isTrue();
        assertThat(status.getStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
        wireMock.verify(getRequestedFor(urlEqualTo(INQUIRY + "000000042"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("X-Api-Key", equalTo(API_KEY)));
    }
}
