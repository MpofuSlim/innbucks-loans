package zw.co.reikan.loans.core.loan;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.groups.Default;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the loan request contract: a QUOTE ({@code Default}) needs only the loan
 * terms, while an APPLICATION ({@code Default} + {@link LoanApplicationChecks})
 * must carry everything the InnBucks pre-approved application uses — the fields
 * whose absence used to be accepted here and fail later at InnBucks.
 */
class LoanRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static LoanRequest quoteOnly() {
        return LoanRequest.builder()
                .amount(new BigDecimal("500.00"))
                .tenor(6)
                .ecnumber("1234567A")
                .mobileNumber("0772123123")
                .nationalId("63-1234567A63")
                .dateOfBirth(LocalDate.of(1990, 5, 14))
                .build();
    }

    static Address address() {
        Address a = new Address();
        a.setStreet("123 Samora Machel Ave");
        a.setCity("Harare");
        return a;
    }

    static LoanRequest completeApplication() {
        EmploymentDetail job = new EmploymentDetail();
        job.setEmployerName("Mutare City Council");
        job.setEmployeeNumber("EMP-001");
        job.setEmploymentStartDate(LocalDate.of(2022, 1, 1));
        job.setGrossSalary(new BigDecimal("1500.00"));

        NextOfKin kin = new NextOfKin();
        kin.setFirstName("Jane");
        kin.setMobileNumber("0772321321");
        kin.setRelationship(RelationshipType.SPOUSE);
        kin.setAddress(address());

        LoanRequest r = quoteOnly();
        r.setFname("James");
        r.setLname("Mufambanaayo");
        r.setMaritalStatus(MaritalStatus.MARRIED);
        r.setPlaceOfBirth("Harare");
        r.setPurposeOfLoan(LoanPurpose.HOME_IMPROVEMENT);
        r.setLineOfBusiness(LineOfBusiness.SERVICES);
        r.setAddress(address());
        r.setEmploymentDetail(job);
        r.setNextOfKin(kin);
        return r;
    }

    private static Set<String> violations(LoanRequest request, Class<?>... groups) {
        Set<ConstraintViolation<LoanRequest>> found = validator.validate(request, groups);
        return found.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a quote needs only the loan terms — the calculator must keep working without applicant details")
    void quoteNeedsOnlyTheTerms() {
        assertThat(violations(quoteOnly(), Default.class)).isEmpty();
    }

    @Test
    @DisplayName("a complete application passes both groups")
    void completeApplicationPasses() {
        assertThat(violations(completeApplication(), Default.class, LoanApplicationChecks.class)).isEmpty();
    }

    @Test
    @DisplayName("an application with only the terms reports every InnBucks-required field at once")
    void applicationWithOnlyTermsListsEveryMissingField() {
        assertThat(violations(quoteOnly(), Default.class, LoanApplicationChecks.class))
                .containsExactlyInAnyOrder(
                        "fname: First name is required",
                        "lname: Last name is required",
                        "maritalStatus: Marital status is required",
                        "placeOfBirth: Place of birth is required",
                        "purposeOfLoan: Purpose of loan is required",
                        "lineOfBusiness: Line of business is required",
                        "address: Address is required",
                        "employmentDetail: Employment detail is required",
                        "nextOfKin: Next of kin is required");
    }

    @Test
    @DisplayName("a partial next of kin is refused field by field — it used to NPE at the InnBucks step")
    void partialNextOfKinIsRefused() {
        LoanRequest r = completeApplication();
        NextOfKin kin = new NextOfKin();
        kin.setFirstName("Jane");
        r.setNextOfKin(kin);

        assertThat(violations(r, Default.class, LoanApplicationChecks.class))
                .containsExactlyInAnyOrder(
                        "nextOfKin.mobileNumber: Next of kin mobile number is required",
                        "nextOfKin.relationship: Next of kin relationship is required",
                        "nextOfKin.address: Next of kin address is required");
    }

    @Test
    @DisplayName("nested addresses need street and city")
    void addressesNeedStreetAndCity() {
        LoanRequest r = completeApplication();
        r.setAddress(new Address());
        r.getNextOfKin().setAddress(new Address());

        assertThat(violations(r, Default.class, LoanApplicationChecks.class))
                .containsExactlyInAnyOrder(
                        "address.street: Street is required",
                        "address.city: City is required",
                        "nextOfKin.address.street: Street is required",
                        "nextOfKin.address.city: City is required");
    }

    @Test
    @DisplayName("employment detail needs employer, employee number, start date and a positive gross salary")
    void employmentDetailIsComplete() {
        LoanRequest r = completeApplication();
        EmploymentDetail job = new EmploymentDetail();
        job.setGrossSalary(BigDecimal.ZERO);
        r.setEmploymentDetail(job);

        assertThat(violations(r, Default.class, LoanApplicationChecks.class))
                .containsExactlyInAnyOrder(
                        "employmentDetail.employerName: Employer name is required",
                        "employmentDetail.employeeNumber: Employee number is required",
                        "employmentDetail.employmentStartDate: Employment start date is required",
                        "employmentDetail.grossSalary: Gross salary must be greater than zero");
    }

    @Test
    @DisplayName("the employer's own address is NOT required — InnBucks never receives it")
    void employerAddressIsNotRequired() {
        LoanRequest r = completeApplication();
        r.getEmploymentDetail().setAddress(new Address());

        assertThat(violations(r, Default.class, LoanApplicationChecks.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0772123123", "772123123", "263772123123", "+263772123123",
            "0712345678", "0732345678", "0782345678"})
    @DisplayName("Zimbabwean mobile numbers in every form people type are accepted")
    void zimbabweanMobilesAccepted(String mobile) {
        LoanRequest r = quoteOnly();
        r.setMobileNumber(mobile);
        assertThat(violations(r, Default.class)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0242123456", "0762123123", "077212312", "07721231234",
            "0772 123 123", "+26877212312", "hello", "+2637721231x"})
    @DisplayName("landlines, unknown prefixes, wrong lengths, spaces and garbage are refused")
    void nonMobilesRefused(String mobile) {
        LoanRequest r = quoteOnly();
        r.setMobileNumber(mobile);
        assertThat(violations(r, Default.class)).containsExactly(
                "mobileNumber: must be a Zimbabwean mobile number, e.g. 0772123123 or +263772123123");
    }

    @Test
    @DisplayName("the next of kin's mobile is held to the same rule on an application")
    void nextOfKinMobileIsValidated() {
        LoanRequest r = completeApplication();
        r.getNextOfKin().setMobileNumber("12345");

        assertThat(violations(r, Default.class, LoanApplicationChecks.class)).containsExactly(
                "nextOfKin.mobileNumber: must be a Zimbabwean mobile number, e.g. 0772123123 or +263772123123");
    }
}
