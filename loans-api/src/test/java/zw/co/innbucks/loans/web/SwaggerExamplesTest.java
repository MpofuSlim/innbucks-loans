package zw.co.innbucks.loans.web;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import zw.co.innbucks.loans.controller.AuthController;
import zw.co.innbucks.loans.controller.BorrowerAuthController;
import zw.co.innbucks.loans.controller.BorrowerController;
import zw.co.innbucks.loans.controller.BorrowerNotificationController;
import zw.co.innbucks.loans.controller.BorrowerStaffLoanController;
import zw.co.innbucks.loans.controller.CommissionGroupController;
import zw.co.innbucks.loans.controller.CreditReasonCodeController;
import zw.co.innbucks.loans.controller.CreditTurnaroundController;
import zw.co.innbucks.loans.controller.CreditWorkbenchController;
import zw.co.innbucks.loans.controller.CurrentUserController;
import zw.co.innbucks.loans.controller.DashboardController;
import zw.co.innbucks.loans.controller.DeductionBatchController;
import zw.co.innbucks.loans.controller.DeductionCancellationController;
import zw.co.innbucks.loans.controller.EmploymentEventController;
import zw.co.innbucks.loans.controller.EmploymentEventTreatmentController;
import zw.co.innbucks.loans.controller.GetMoreController;
import zw.co.innbucks.loans.controller.GetMoreSimulatorController;
import zw.co.innbucks.loans.controller.HeldBookingController;
import zw.co.innbucks.loans.controller.InstrumentTemplateController;
import zw.co.innbucks.loans.controller.LoanApplicationDraftController;
import zw.co.innbucks.loans.controller.LoanController;
import zw.co.innbucks.loans.controller.LoanDocumentController;
import zw.co.innbucks.loans.controller.LoanNotificationController;
import zw.co.innbucks.loans.controller.MerchantController;
import zw.co.innbucks.loans.controller.PayslipReviewController;
import zw.co.innbucks.loans.controller.ReportController;
import zw.co.innbucks.loans.controller.SignedInstrumentController;
import zw.co.innbucks.loans.controller.StaffGradeLimitController;
import zw.co.innbucks.loans.controller.StaffArrearsOverrideController;
import zw.co.innbucks.loans.controller.StaffLimitOverrideController;
import zw.co.innbucks.loans.controller.StaffLoanArrearsReportController;
import zw.co.innbucks.loans.controller.StaffLoanCollectionController;
import zw.co.innbucks.loans.controller.StaffLoanWriteOffController;
import zw.co.innbucks.loans.controller.StaffLoanController;
import zw.co.innbucks.loans.controller.StaffLoanMerchantController;
import zw.co.innbucks.loans.controller.StaffLoanVoucherIssuanceController;
import zw.co.innbucks.loans.controller.StaffNotificationController;
import zw.co.innbucks.loans.controller.StaffOfferController;
import zw.co.innbucks.loans.controller.StaffRegisterController;
import zw.co.innbucks.loans.controller.StaffRegisterReconciliationController;
import zw.co.innbucks.loans.controller.TestAssertionController;
import zw.co.innbucks.loans.controller.UserController;
import zw.co.innbucks.loans.controller.VoucherController;
import zw.co.innbucks.loans.controller.VoucherRedemptionController;
import zw.co.innbucks.loans.controller.CheckpointController;
import zw.co.innbucks.loans.controller.CreditAuthorityController;
import zw.co.innbucks.loans.controller.WorkQueueController;
import zw.co.innbucks.loans.controller.WorkflowStageController;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.loan.CreditWorkbenchService;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanAgreement;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanJourneyService;
import zw.co.innbucks.loans.core.voucher.VoucherCodes;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Every example the API documentation shows is what a client will code against, so each one must
 * parse as JSON and be in the envelope: a {@code code}, a {@code message}, and no field outside
 * {@code code/message/data}. Examples are assembled from shared fragments ({@link ApiExamples}),
 * which is exactly how a stray comma gets in. Every controller is also held to the base path.
 */
class SwaggerExamplesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final List<Class<?>> CONTROLLERS = List.of(AuthController.class, CommissionGroupController.class,
            CreditReasonCodeController.class, CreditTurnaroundController.class, CreditWorkbenchController.class,
            CurrentUserController.class, DashboardController.class,
            DeductionBatchController.class, DeductionCancellationController.class, EmploymentEventController.class,
            EmploymentEventTreatmentController.class, HeldBookingController.class,
            InstrumentTemplateController.class, LoanApplicationDraftController.class, LoanController.class,
            LoanDocumentController.class, LoanNotificationController.class, PayslipReviewController.class,
            MerchantController.class, ReportController.class, SignedInstrumentController.class, UserController.class,
            WorkflowStageController.class, WorkQueueController.class, CheckpointController.class,
            CreditAuthorityController.class, StaffGradeLimitController.class,
            StaffRegisterController.class, StaffRegisterReconciliationController.class, StaffOfferController.class,
            StaffLimitOverrideController.class, StaffNotificationController.class, VoucherController.class,
            VoucherRedemptionController.class, BorrowerAuthController.class, TestAssertionController.class,
            BorrowerController.class, BorrowerStaffLoanController.class, StaffLoanController.class,
            BorrowerNotificationController.class, StaffLoanMerchantController.class,
            StaffArrearsOverrideController.class, StaffLoanWriteOffController.class,
            StaffLoanArrearsReportController.class, GetMoreController.class, GetMoreSimulatorController.class,
            StaffLoanVoucherIssuanceController.class, StaffLoanCollectionController.class);

    record Example(String where, String json) {
        @Override
        public String toString() {
            return where;
        }
    }

    static Stream<Example> examples() {
        List<Example> found = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            collect(controller.getSimpleName(), controller, found);
            for (Method method : controller.getDeclaredMethods()) {
                collect(controller.getSimpleName() + "." + method.getName(), method, found);
            }
        }
        return found.stream();
    }

    private static void collect(String where, AnnotatedElement element, List<Example> found) {
        List<ApiResponse> responses = new ArrayList<>();
        ApiResponses many = element.getAnnotation(ApiResponses.class);
        if (many != null) {
            responses.addAll(Arrays.asList(many.value()));
        }
        ApiResponse one = element.getAnnotation(ApiResponse.class);
        if (one != null) {
            responses.add(one);
        }
        for (ApiResponse response : responses) {
            for (Content content : response.content()) {
                for (ExampleObject example : content.examples()) {
                    String name = example.name().isEmpty() ? "" : " (" + example.name() + ")";
                    found.add(new Example(where + " " + response.responseCode() + name, example.value()));
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void everyExampleIsAValidEnvelope(Example example) {
        JsonNode body = assertDoesNotThrow(() -> JSON.readTree(example.json()), example.where());
        assertThat(body.isObject()).as(example.where()).isTrue();
        assertThat(body.path("code").isString()).as(example.where() + " has a code").isTrue();
        assertThat(body.path("message").isString()).as(example.where() + " has a message").isTrue();
        List<String> fields = new ArrayList<>();
        body.propertyNames().forEach(fields::add);
        assertThat(fields).as(example.where()).isSubsetOf(Set.of("code", "message", "data"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("controllers")
    void everyControllerLivesUnderTheBasePath(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.path()).allSatisfy(path -> assertThat(path).startsWith(ApiPaths.BASE));
    }

    @Test
    void theDecisionLogExampleCarriesTheRealHashOfEachSnapshot() {
        // The API returns each snapshot as stored, so a client can re-hash it; the example must hold up too.
        String example = ApiExamples.CREDIT_DECISION_LOG;
        JsonNode entries = JSON.readTree(example).path("data");
        int from = 0;
        for (JsonNode entry : entries) {
            int start = example.indexOf("\"loanSnapshot\": ", from) + "\"loanSnapshot\": ".length();
            int end = start;
            for (int depth = 0; ; end++) {
                char c = example.charAt(end);
                depth += c == '{' ? 1 : c == '}' ? -1 : 0;
                if (depth == 0 && c == '}') {
                    break;
                }
            }
            String snapshot = example.substring(start, end + 1);
            assertThat(AuditService.sha256Hex(snapshot)).isEqualTo(entry.path("snapshotSha256").asString());
            from = end;
        }
        assertThat(entries.size()).isEqualTo(3);
    }

    @Test
    void theInstrumentExamplesCarryTheRealHashOfTheirText() {
        // A client can re-hash what it was shown or what was signed; the examples must hold up too.
        for (String example : List.of(ApiExamples.INSTRUMENT_PREVIEW, ApiExamples.LOAN_43_SIGNED_INSTRUMENTS)) {
            JsonNode instruments = JSON.readTree(example).path("data");
            assertThat(instruments.size()).isEqualTo(2);
            for (JsonNode instrument : instruments) {
                assertThat(AuditService.sha256Hex(instrument.path("content").asString()))
                        .isEqualTo(instrument.path("contentSha256").asString());
            }
        }
    }

    @Test
    void theNotificationExampleCarriesTheRealWordingOfEachNotice() {
        // A client shows the applicant's history as sent; the example must read like what is actually sent.
        Loan loan = Loan.builder().disbursedAmount(new BigDecimal("300.00")).build();
        loan.setId(43L);
        JsonNode notifications = JSON.readTree(ApiExamples.LOAN_43_NOTIFICATIONS).path("data");
        assertThat(notifications.size()).isEqualTo(3);
        for (JsonNode notification : notifications) {
            LoanNotice notice = LoanNotice.valueOf(notification.path("notice").asString());
            assertThat(notification.path("message").asString()).isEqualTo(notice.textFor(loan));
            assertThat(notification.path("stage").asString()).isEqualTo(notice.stage().name());
        }
    }

    @Test
    void theWorkbenchExampleAddsUpAndCarriesTheRealAffordabilityNote() {
        // The figures a credit officer reads must be the ones the service would compute from the example's own loan.
        JsonNode data = JSON.readTree(ApiExamples.LOAN_42_CREDIT_WORKBENCH).path("data");
        JsonNode loan = data.path("loan");
        JsonNode affordability = data.path("affordability");
        BigDecimal deductions = BigDecimal.ZERO;
        for (JsonNode deduction : loan.path("payslipDeductions")) {
            deductions = deductions.add(deduction.path("amount").decimalValue());
        }
        assertThat(affordability.path("payslipDeductions").decimalValue()).isEqualByComparingTo(deductions);
        assertThat(affordability.path("grossSalary").decimalValue())
                .isEqualByComparingTo(loan.path("employmentDetail").path("grossSalary").decimalValue());
        BigDecimal net = loan.path("employmentDetail").path("netSalary").decimalValue();
        BigDecimal deduction = loan.path("grossedMonthlyDeduction").decimalValue();
        assertThat(affordability.path("monthlyDeduction").decimalValue()).isEqualByComparingTo(deduction);
        assertThat(affordability.path("netAfterDeduction").decimalValue()).isEqualByComparingTo(net.subtract(deduction));
        assertThat(affordability.path("deductionToNetPercent").decimalValue()).isEqualByComparingTo(
                deduction.multiply(BigDecimal.valueOf(100)).divide(net, 1, RoundingMode.HALF_UP));
        assertThat(affordability.path("note").asString()).isEqualTo(CreditWorkbenchService.AFFORDABILITY_NOT_ASSESSED);
        assertThat(data.path("decisions").size()).isEqualTo(2);
    }

    @Test
    void theReconciliationExampleAddsUp() {
        // The summary's counts are what the report lists; a reader comparing the two must find the same numbers.
        JsonNode summary = JSON.readTree(ApiExamples.STAFF_RECONCILED).path("data");
        JsonNode variances = JSON.readTree(ApiExamples.STAFF_RECONCILIATION_VARIANCES).path("data").path("items");
        Map<String, Integer> byKind = new HashMap<>();
        Map<String, Integer> eligible = new HashMap<>();
        for (JsonNode variance : variances) {
            byKind.merge(variance.path("kind").asString(), 1, Integer::sum);
            eligible.merge(variance.path("kind").asString(), variance.path("eligible").asBoolean(false) ? 1 : 0,
                    Integer::sum);
        }
        assertThat(byKind).containsExactlyInAnyOrderEntriesOf(Map.of(
                "LEFT_ON_PAYROLL", summary.path("leftOnPayroll").asInt(),
                "NOT_ON_PAYROLL", summary.path("notOnPayroll").asInt(),
                "DIFFERENT", summary.path("different").asInt(),
                "NOT_ON_REGISTER", summary.path("notOnRegister").asInt(),
                "DUPLICATE_ON_PAYROLL", summary.path("duplicatesOnPayroll").asInt(),
                "UNREADABLE", summary.path("unreadableRows").asInt()));
        assertThat(eligible.get("LEFT_ON_PAYROLL")).isEqualTo(summary.path("leftOnPayrollEligible").asInt());
        assertThat(eligible.get("NOT_ON_PAYROLL")).isEqualTo(summary.path("notOnPayrollEligible").asInt());
        assertThat(variances.size()).isEqualTo(summary.path("variances").asInt());
        assertThat(JSON.readTree(ApiExamples.STAFF_RECONCILIATION_1).path("data"))
                .isEqualTo(((ObjectNode) summary.deepCopy()).without("ignoredColumns"));
    }

    @Test
    void theVoucherExamplesCarryARealCodeAndAgreeOnIt() {
        // The code a reader sees in full is one the till would accept, and the masked form everywhere else is its tail.
        String code = JSON.readTree(VoucherApiExamples.VOUCHER_7_CODE).path("data").path("scanValue").asString();
        assertThat(code).isEqualTo(VoucherApiExamples.CODE_7);
        assertThat(VoucherCodes.normalize(code)).contains(code);
        assertThat(VoucherCodes.normalize(JSON.readTree(VoucherApiExamples.VALIDATION_REQUEST).path("code")
                .asString())).contains(code);
        assertThat(VoucherCodes.normalize(JSON.readTree(VoucherApiExamples.REDEMPTION_REQUEST).path("code")
                .asString())).contains(code);
        assertThat(JSON.readTree(VoucherApiExamples.VOUCHER_7_DETAIL).path("data").path("voucher").path("maskedCode")
                .asString()).isEqualTo("**** **** **** " + code.substring(12));
        assertThat(VoucherCodes.normalize("7391045288672151")).as("voucher 8, masked as **** 2151").isPresent();
        JsonNode detail = JSON.readTree(VoucherApiExamples.VOUCHER_7_DETAIL).path("data");
        JsonNode redemption = detail.path("redemptions").get(0);
        assertThat(detail.path("voucher").path("faceValue").decimalValue()
                .subtract(redemption.path("amount").decimalValue()))
                .isEqualByComparingTo(detail.path("voucher").path("balance").decimalValue())
                .isEqualByComparingTo(redemption.path("balanceAfter").decimalValue());
    }

    @Test
    void theStaffLoanAgreementExamplesCarryTheRealHashesOfTheirText() {
        // The app sends the quote's contentSha256 back at Accept, and the portal shows the seal over the acceptance;
        // a reader re-hashing either example must get what it says.
        JsonNode quoted = JSON.readTree(StaffLoanApiExamples.QUOTE).path("data").path("agreement");
        JsonNode signed = JSON.readTree(StaffLoanApiExamples.STAFF_LOAN_DETAIL).path("data");
        JsonNode agreement = signed.path("agreement");
        for (JsonNode text : List.of(quoted, agreement)) {
            assertThat(AuditService.sha256Hex(text.path("content").asString()))
                    .isEqualTo(text.path("contentSha256").asString())
                    .isEqualTo(StaffLoanApiExamples.AGREEMENT_SHA256);
        }
        assertThat(JSON.readTree(StaffLoanApiExamples.ACCEPT_REQUEST).path("agreementSha256").asString())
                .isEqualTo(StaffLoanApiExamples.AGREEMENT_SHA256);
        StaffLoanAgreement sealed = StaffLoanAgreement.builder()
                .staffLoanId(signed.path("loan").path("id").asLong())
                .instrumentType(InstrumentType.valueOf(agreement.path("instrumentType").asString()))
                .templateVersion(agreement.path("templateVersion").asInt())
                .title(agreement.path("title").asString())
                .contentSha256(agreement.path("contentSha256").asString())
                .acceptedBy(agreement.path("acceptedBy").asString())
                .acceptedAt(OffsetDateTime.parse(agreement.path("acceptedAt").asString())
                        .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime())
                .deviceId(agreement.path("deviceId").asString())
                .ipAddress(agreement.path("ipAddress").asString())
                .forwardedFor(agreement.path("forwardedFor").asString())
                .userAgent(agreement.path("userAgent").asString())
                .authenticationMethod(agreement.path("authenticationMethod").asString())
                .assertionId(agreement.path("assertionId").asString())
                .build();
        assertThat(StaffLoanJourneyService.evidenceSha256(sealed))
                .isEqualTo(agreement.path("evidenceSha256").asString());
    }

    @Test
    void theFlaggedLoanIsTheOneVoucherEightWasIssuedFor() {
        // Nyasha Dube (E1001) took offer 1 up as SGL-2026-000151, paid out as voucher 8, then resigned: the flagged
        // loan in the portal must be that loan, with that offer, that payout and that voucher's value.
        JsonNode loan = JSON.readTree(StaffLoanApiExamples.STAFF_LOANS_FLAGGED).path("data").path("items").get(0);
        JsonNode voucher = null;
        for (JsonNode item : JSON.readTree(VoucherApiExamples.VOUCHERS).path("data").path("items")) {
            if (item.path("id").asLong() == 8) {
                voucher = item;
            }
        }
        assertThat(voucher).isNotNull();
        assertThat(loan.path("reference").asString()).isEqualTo(voucher.path("loanAccount").asString());
        assertThat(loan.path("disbursementReference").asString())
                .isEqualTo(voucher.path("disbursementReference").asString());
        assertThat(loan.path("amount").decimalValue()).isEqualByComparingTo(voucher.path("faceValue").decimalValue());
        assertThat(loan.path("employeeNumber").asString()).isEqualTo(voucher.path("customerReference").asString());
        assertThat(loan.path("fullName").asString()).isEqualTo(voucher.path("customerName").asString());
        assertThat(loan.path("msisdn").asString()).isEqualTo(voucher.path("customerMsisdn").asString());
        assertThat(OffsetDateTime.parse(loan.path("disbursedAt").asString()))
                .isBefore(OffsetDateTime.parse(voucher.path("issuedAt").asString()));

        JsonNode offer = null;
        for (JsonNode item : JSON.readTree(ApiExamples.STAFF_OFFERS).path("data").path("items")) {
            if (item.path("employeeNumber").asString().equals(loan.path("employeeNumber").asString())) {
                offer = item;
            }
        }
        assertThat(offer).isNotNull();
        assertThat(offer.path("id").asLong()).isEqualTo(loan.path("offerId").asLong());
        assertThat(OffsetDateTime.parse(loan.path("acceptedAt").asString()))
                .isBefore(OffsetDateTime.parse(offer.path("expiresAt").asString()));
        assertThat(loan.path("employmentFlag").path("action").asString()).isEqualTo("RECOVER_FROM_TERMINAL_BENEFITS");
    }

    @Test
    void theStaffGroceryLoanExamplesTellOneStory() {
        // Chipo Banda (E1012) takes offer 2 up as SGL-2026-000143 and it becomes voucher 7: whichever screen a reader
        // starts from, the offer, the loan, the phone and the dates must be the same ones.
        JsonNode offer = JSON.readTree(StaffLoanApiExamples.HOME_OFFER).path("data").path("offer");
        JsonNode loan = JSON.readTree(StaffLoanApiExamples.STAFF_LOAN_DETAIL).path("data").path("loan");
        JsonNode listed = null;
        for (JsonNode item : JSON.readTree(ApiExamples.STAFF_OFFERS).path("data").path("items")) {
            if (item.path("employeeNumber").asString().equals(loan.path("employeeNumber").asString())) {
                listed = item;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.path("id").asLong()).isEqualTo(offer.path("offerId").asLong())
                .isEqualTo(loan.path("offerId").asLong())
                .isEqualTo(JSON.readTree(StaffLoanApiExamples.QUOTE_REQUEST).path("offerId").asLong())
                .isEqualTo(JSON.readTree(StaffLoanApiExamples.ACCEPT_REQUEST).path("offerId").asLong())
                .isEqualTo(JSON.readTree(BorrowerApiExamples.INBOX).path("data").path("items").get(0)
                        .path("offerId").asLong());
        assertThat(listed.path("expiresAt").asString()).isEqualTo(offer.path("expiresAt").asString());
        assertThat(listed.path("closedReason").asString()).isEqualTo("Taken up as " + loan.path("reference").asString());
        assertThat(listed.path("closedAt").asString()).isEqualTo(loan.path("acceptedAt").asString());
        assertThat(JSON.readTree(StaffLoanApiExamples.APPLIED).path("data").path("offer").path("offerId").asLong())
                .as("an Apply offer is a new offer, not the run's").isNotEqualTo(listed.path("id").asLong());

        JsonNode voucher7 = JSON.readTree(VoucherApiExamples.VOUCHER_7_DETAIL).path("data").path("voucher");
        assertThat(voucher7.path("loanAccount").asString()).isEqualTo(loan.path("reference").asString());
        assertThat(voucher7.path("issuedAt").asString()).isGreaterThan(loan.path("acceptedAt").asString());
        Map<String, String> registerPhones = new HashMap<>();
        for (JsonNode member : JSON.readTree(ApiExamples.STAFF_MEMBERS).path("data").path("items")) {
            registerPhones.put(member.path("employeeNumber").asString(),
                    MsisdnUtils.mask(member.path("mobileNumber").asString()));
        }
        assertThat(List.of(loan.path("msisdn").asString(), voucher7.path("customerMsisdn").asString(),
                JSON.readTree(BorrowerApiExamples.PROFILE).path("data").path("maskedMsisdn").asString(),
                JSON.readTree(VoucherApiExamples.VOUCHER_7_DETAIL).path("data").path("deliveries").get(0)
                        .path("recipient").asString()))
                .containsOnly(registerPhones.get("E1012"));
        for (JsonNode voucher : JSON.readTree(VoucherApiExamples.VOUCHERS).path("data").path("items")) {
            assertThat(voucher.path("customerMsisdn").asString()).as(voucher.path("customerReference").asString())
                    .isEqualTo(registerPhones.get(voucher.path("customerReference").asString()));
        }
    }

    @Test
    void theGetMoreExamplesTellOneStory() {
        // Rutendo Chari (E1064) accepts SGL-2026-000164 for USD 150.00 and GetMore's (simulated) store issues it as
        // coupon 1207, voucher 9; she spends USD 45.50 and USD 54.50 at Avondale and USD 10.00 is refunded, leaving
        // USD 60.00. Every screen a reader starts from must show that phone, that loan, that code and those balances.
        JsonNode member = null;
        for (JsonNode item : JSON.readTree(ApiExamples.STAFF_MEMBERS).path("data").path("items")) {
            if (item.path("employeeNumber").asString().equals("E1064")) {
                member = item;
            }
        }
        assertThat(member).isNotNull();
        String phone = MsisdnUtils.mask(member.path("mobileNumber").asString());
        assertThat(phone).isEqualTo("****0064");

        JsonNode accepted = JSON.readTree(StaffLoanApiExamples.ACCEPTED_WITH_VOUCHER).path("data");
        JsonNode afterSale = JSON.readTree(GetMoreApiExamples.SALE).path("data").path("voucher");
        JsonNode retried = JSON.readTree(GetMoreApiExamples.RETRIED).path("data");
        String reference = accepted.path("reference").asString();
        assertThat(reference).isEqualTo("SGL-2026-000164")
                .isEqualTo(afterSale.path("loanAccount").asString())
                .isEqualTo(retried.path("loan").path("reference").asString())
                .isEqualTo(retried.path("issuance").path("loanReference").asString())
                .isEqualTo(JSON.readTree(GetMoreApiExamples.SALE_REQUEST).path("loanReference").asString())
                .isEqualTo(JSON.readTree(GetMoreApiExamples.LOOKUP_REQUEST).path("loanReference").asString())
                .isEqualTo(JSON.readTree(StaffLoanApiExamples.VOUCHER_SPENDS).path("data").path("loanReference")
                        .asString());
        assertThat(List.of(afterSale.path("customerMsisdn").asString(), retried.path("loan").path("msisdn")
                .asString())).containsOnly(phone);
        assertThat(retried.path("loan").path("staffMemberId").asLong()).isEqualTo(member.path("id").asLong());
        assertThat(retried.path("loan").path("employeeNumber").asString())
                .isEqualTo(afterSale.path("customerReference").asString()).isEqualTo("E1064");

        // The code: in full where she sees it, masked as its last four everywhere else, on the issuance's prefix,
        // which is on the host's configured one.
        String code = GetMoreApiExamples.CODE_9;
        assertThat(accepted.path("voucher").path("code").asString()).isEqualTo(code);
        assertThat(JSON.readTree(GetMoreApiExamples.VOUCHER_9_CODE).path("data").path("scanValue").asString())
                .isEqualTo(code);
        assertThat(GetMoreCodes.normalize(code)).contains(code);
        assertThat(List.of(afterSale.path("maskedCode").asString(), accepted.path("voucher").path("maskedCode")
                        .asString(), JSON.readTree(GetMoreApiExamples.SALE).path("data").path("store").path("order")
                        .path("coupon_code").asString()))
                .containsOnly(GetMoreCodes.maskedCode(code));
        String prefix = retried.path("issuance").path("couponPrefix").asString();
        assertThat(code).startsWith(prefix);
        assertThat(prefix).startsWith(JSON.readTree(GetMoreApiExamples.STATUS_SIMULATED).path("data")
                .path("couponPrefix").asString());
        assertThat(afterSale.path("disbursementReference").asString())
                .isEqualTo("GETMORE-SIM-" + afterSale.path("externalCouponId").asLong())
                .isEqualTo(retried.path("loan").path("disbursementReference").asString());
        assertThat(afterSale.path("id").asLong()).isEqualTo(9)
                .isEqualTo(retried.path("issuance").path("voucherId").asLong())
                .isEqualTo(JSON.readTree(GetMoreApiExamples.ISSUANCES).path("data").path("items").get(0)
                        .path("voucherId").asLong());

        // The balances: each step's voucher, the store's own balance and the borrower's tile add up.
        BigDecimal face = accepted.path("voucher").path("faceValue").decimalValue();
        assertThat(face).isEqualByComparingTo(accepted.path("amount").decimalValue())
                .isEqualByComparingTo(afterSale.path("faceValue").decimalValue())
                .isEqualByComparingTo(JSON.readTree(GetMoreApiExamples.LOOKUP).path("data").path("allowance")
                        .decimalValue());
        BigDecimal first = JSON.readTree(GetMoreApiExamples.SALE_REQUEST).path("amount").decimalValue();
        BigDecimal second = JSON.readTree(GetMoreApiExamples.SALE_HELD_REQUEST).path("amount").decimalValue();
        BigDecimal refund = JSON.readTree(GetMoreApiExamples.REFUND_REQUEST).path("amount").decimalValue();
        assertThat(afterSale.path("balance").decimalValue()).isEqualByComparingTo(face.subtract(first));
        assertThat(JSON.readTree(GetMoreApiExamples.INVOICED).path("data").path("voucher").path("balance")
                .decimalValue()).isEqualByComparingTo(face.subtract(first).subtract(second));
        BigDecimal left = face.subtract(first).subtract(second).add(refund);
        JsonNode refunded = JSON.readTree(GetMoreApiExamples.REFUNDED).path("data");
        assertThat(refunded.path("voucher").path("balance").decimalValue()).isEqualByComparingTo(left);
        assertThat(refunded.path("store").path("balance").decimalValue()).isEqualByComparingTo(left);
        assertThat(refunded.path("voucher").path("refundedAmount").decimalValue()).isEqualByComparingTo(refund);
        assertThat(JSON.readTree(GetMoreApiExamples.LOOKUP).path("data").path("balance").decimalValue())
                .isEqualByComparingTo(left);
        JsonNode tile = JSON.readTree(StaffLoanApiExamples.HOME_GETMORE_VOUCHER).path("data").path("loan")
                .path("voucher");
        assertThat(tile.path("balance").decimalValue()).isEqualByComparingTo(left);
        assertThat(tile.path("spent").decimalValue()).isEqualByComparingTo(face.subtract(left));
        assertThat(JSON.readTree(GetMoreApiExamples.EXPIRED).path("data").path("voucher").path("balance")
                .decimalValue()).isEqualByComparingTo(left);
        assertThat(JSON.readTree(GetMoreApiExamples.EXPIRED).path("message").asString())
                .isEqualTo("Voucher 9 expired now (simulation) with USD " + left.setScale(2, RoundingMode.UNNECESSARY)
                        + " unspent");

        JsonNode spends = JSON.readTree(StaffLoanApiExamples.VOUCHER_SPENDS).path("data");
        BigDecimal running = face;
        for (JsonNode movement : spends.path("movements")) {
            BigDecimal amount = movement.path("amount").decimalValue();
            running = "REFUND".equals(movement.path("kind").asString()) ? running.add(amount)
                    : running.subtract(amount);
            assertThat(movement.path("balanceAfter").decimalValue()).as(movement.path("orderNumber").asString())
                    .isEqualByComparingTo(running);
        }
        assertThat(running).isEqualByComparingTo(left)
                .isEqualByComparingTo(spends.path("balance").decimalValue());
        assertThat(spends.path("spent").decimalValue()).isEqualByComparingTo(face.subtract(left));
        assertThat(spends.path("balanceAsOf").asString()).isEqualTo(tile.path("balanceAsOf").asString());

        // Staff's view of the same voucher: its sales and refund walk the balance down to what is left, each named by
        // the order the borrower's list names, and the day's settlement report adds up to the same money.
        JsonNode detail = JSON.readTree(GetMoreApiExamples.VOUCHER_9_DETAIL).path("data");
        assertThat(detail.path("voucher").path("balance").decimalValue()).isEqualByComparingTo(left);
        BigDecimal walked = face;
        List<String> orders = new ArrayList<>();
        for (JsonNode sale : detail.path("redemptions")) {
            walked = walked.subtract(sale.path("amount").decimalValue());
            assertThat(sale.path("balanceAfter").decimalValue()).isEqualByComparingTo(walked);
            assertThat(sale.path("source").asString()).isEqualTo("GETMORE_SYNC");
            orders.add(sale.path("externalOrderNumber").asString());
        }
        for (JsonNode given : detail.path("refunds")) {
            walked = walked.add(given.path("amount").decimalValue());
            assertThat(given.path("balanceAfter").decimalValue()).isEqualByComparingTo(walked);
        }
        assertThat(walked).isEqualByComparingTo(left);
        List<String> spentOn = new ArrayList<>();
        spends.path("movements").forEach(movement -> {
            if ("SPEND".equals(movement.path("kind").asString())) {
                spentOn.add(movement.path("orderNumber").asString());
            }
        });
        assertThat(orders).isEqualTo(spentOn);
        assertThat(detail.path("refunds").get(0).path("amount").decimalValue()).isEqualByComparingTo(refund);

        JsonNode report = JSON.readTree(GetMoreApiExamples.SETTLEMENT_REPORT_GETMORE).path("data");
        JsonNode totals = report.path("totals").get(0);
        assertThat(totals.path("issuedValue").decimalValue()).isEqualByComparingTo(face);
        assertThat(totals.path("redeemedValue").decimalValue()).isEqualByComparingTo(first.add(second));
        assertThat(totals.path("refundedValue").decimalValue()).isEqualByComparingTo(refund);
        assertThat(totals.path("netRedeemedValue").decimalValue()).isEqualByComparingTo(face.subtract(left));
        assertThat(report.path("byMerchant").get(0).path("netRedeemedValue"))
                .isEqualTo(totals.path("netRedeemedValue"));
        assertThat(report.path("redemptionsByOutlet").get(0).path("netRedeemedValue"))
                .isEqualTo(totals.path("netRedeemedValue"));
        assertThat(report.path("simulated").asBoolean()).isTrue();
        List<String> references = new ArrayList<>();
        for (JsonNode line : report.path("lines")) {
            if (line.hasNonNull("merchantReference")) {
                references.add(line.path("merchantReference").asString());
            }
            assertThat(line.path("voucherId").asLong()).isEqualTo(9);
            assertThat(line.path("loanAccount").asString()).isEqualTo(reference);
            assertThat(line.path("maskedCode").asString()).isEqualTo(GetMoreCodes.maskedCode(code));
            assertThat(line.has("occurredAt")).as(line.path("event").asString())
                    .isEqualTo(!"ISSUED".equals(line.path("event").asString()));
        }
        assertThat(references).contains(detail.path("redemptions").get(0).path("merchantReference").asString(),
                        detail.path("redemptions").get(1).path("merchantReference").asString(),
                        detail.path("refunds").get(0).path("merchantReference").asString());
    }

    @Test
    void theCollectionExamplesTellTheGetMoreStory() {
        // On payday, 20 November, Rutendo's SGL-2026-000164 is collected in full (the debt stands) and GetMore is paid
        // what voucher 9 sold less what it refunded. Every figure on the statement must be the GetMore story's own.
        JsonNode accepted = JSON.readTree(StaffLoanApiExamples.ACCEPTED_WITH_VOUCHER).path("data");
        JsonNode expired = JSON.readTree(GetMoreApiExamples.EXPIRED).path("data").path("voucher");
        BigDecimal first = JSON.readTree(GetMoreApiExamples.SALE_REQUEST).path("amount").decimalValue();
        BigDecimal second = JSON.readTree(GetMoreApiExamples.SALE_HELD_REQUEST).path("amount").decimalValue();
        BigDecimal refund = JSON.readTree(GetMoreApiExamples.REFUND_REQUEST).path("amount").decimalValue();
        JsonNode member = null;
        for (JsonNode item : JSON.readTree(ApiExamples.STAFF_MEMBERS).path("data").path("items")) {
            if (item.path("employeeNumber").asString().equals("E1064")) {
                member = item;
            }
        }
        assertThat(member).isNotNull();

        JsonNode report = JSON.readTree(StaffLoanCollectionApiExamples.REPORT);
        JsonNode before = JSON.readTree(StaffLoanCollectionApiExamples.REPORT_BEFORE_SYNC);
        JsonNode finalised = JSON.readTree(StaffLoanCollectionApiExamples.FINALISED);
        for (JsonNode body : List.of(report, before, finalised)) {
            JsonNode data = body.path("data");
            assertThat(body.path("message").asString()).contains(StaffLoanCollectionService.NOT_FOR_PAYROLL);
            assertThat(data.path("payday").asString()).isEqualTo(accepted.path("repaymentDate").asString());
            assertThat(data.path("simulated").asBoolean()).isTrue();
            JsonNode line = data.path("lines").get(0);
            assertThat(line.path("loanReference").asString()).isEqualTo(accepted.path("reference").asString());
            assertThat(line.path("staffLoanId").asLong()).isEqualTo(164);
            assertThat(line.path("employeeNumber").asString()).isEqualTo(member.path("employeeNumber").asString());
            assertThat(line.path("fullName").asString()).isEqualTo(member.path("fullName").asString());
            assertThat(line.path("voucherId").asLong()).isEqualTo(expired.path("id").asLong());
            assertThat(line.path("voucherStateAtCutoff").asString()).isEqualTo(expired.path("status").asString());
            assertThat(line.path("stoppedReason").asString()).isEqualTo(expired.path("stoppedReason").asString());
            assertThat(line.path("faceValue").decimalValue()).isEqualByComparingTo(accepted.path("amount")
                    .decimalValue());
            assertThat(line.path("spentNet").decimalValue()).isEqualByComparingTo(expired.path("redeemedAmount")
                    .decimalValue()).isEqualByComparingTo(first.add(second).subtract(refund));
            assertThat(line.path("refunded").decimalValue()).isEqualByComparingTo(refund);
            assertThat(line.path("treatment").asString()).isEqualTo("DEBT_STANDS");
            assertThat(line.path("amountToCollect").decimalValue()).as("the debt stands")
                    .isEqualByComparingTo(accepted.path("totalRepayable").decimalValue());
            assertThat(data.path("totals").get(0).path("amountToCollect").decimalValue())
                    .isEqualByComparingTo(line.path("amountToCollect").decimalValue());
            JsonNode settlement = data.path("merchantSettlement");
            assertThat(settlement.path("merchantName").asString()).isEqualTo(accepted.path("merchantName").asString());
            assertThat(settlement.path("totals").get(0).path("spends").decimalValue())
                    .isEqualByComparingTo(first.add(second));
            assertThat(settlement.path("totals").get(0).path("refunds").decimalValue()).isEqualByComparingTo(refund);
            assertThat(settlement.path("totals").get(0).path("net").decimalValue())
                    .isEqualByComparingTo(line.path("spentNet").decimalValue());
            assertThat(settlement.path("lines").get(0).path("voucherId").asLong()).isEqualTo(9);
        }
        // Before the payday sync the voucher was last read when the simulator expired it: stale, and not final.
        JsonNode stale = before.path("data").path("lines").get(0);
        assertThat(stale.path("lastSyncedAt").asString()).isEqualTo(expired.path("syncedAt").asString());
        assertThat(stale.path("final").asBoolean()).isFalse();
        assertThat(stale.path("flags").get(0).asString()).isEqualTo("STALE");
        assertThat(before.path("data").path("merchantSettlement").path("staleVouchers").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(StaffLoanCollectionApiExamples.NOT_FINAL).path("message").asString())
                .startsWith("1 line is not final and 1 voucher is stale");
        assertThat(JSON.readTree(StaffLoanCollectionApiExamples.SETTLED_CONCURRENTLY).path("message").asString())
                .isEqualTo(StaffLoanCollectionService.settledConcurrently(LocalDate.parse(
                        accepted.path("repaymentDate").asString())));
        // After it, and as finalised: the same line, final.
        assertThat(finalised.path("data").path("lines")).isEqualTo(report.path("data").path("lines"));
        assertThat(report.path("data").path("lines").get(0).path("final").asBoolean()).isTrue();
        assertThat(finalised.path("message").asString()).isEqualTo("Collections for 20 Nov 2026 finalised: 1 loan,"
                + " USD 150.00 to collect; USD 90.00 to pay GetMore Groceries (SIMULATED: not for payroll)");
        assertThat(OffsetDateTime.parse(finalised.path("data").path("finalisedAt").asString()))
                .isAfter(OffsetDateTime.parse(report.path("data").path("lines").get(0).path("lastSyncedAt")
                        .asString()));
    }

    static Stream<Class<?>> controllers() {
        return CONTROLLERS.stream();
    }

    @Test
    void theLoanHistoryShowsTheSameLoanAndVoucherAsTheTile() {
        // In December Chipo's list holds SGL-2026-000143 repaid: the loan the tile showed in October, with voucher 7
        // lapsed at what was left of it. Its terms, dates and voucher must be the ones every other screen shows.
        JsonNode tile = JSON.readTree(StaffLoanApiExamples.HOME_VOUCHER).path("data").path("loan");
        JsonNode items = JSON.readTree(StaffLoanApiExamples.LOAN_HISTORY).path("data").path("items");
        JsonNode repaid = items.get(1);
        assertThat(repaid.path("reference").asString()).isEqualTo(tile.path("reference").asString());
        for (String field : List.of("amount", "totalRepayable", "repaymentDate", "acceptedAt", "merchantName")) {
            assertThat(repaid.path(field)).as(field).isEqualTo(tile.path(field));
        }
        JsonNode voucher = repaid.path("voucher");
        for (String field : List.of("faceValue", "balance", "expiresAt", "maskedCode")) {
            assertThat(voucher.path(field)).as(field).isEqualTo(tile.path("voucher").path(field));
        }
        assertThat(voucher.path("code").isNull()).as("a lapsed voucher's code is not shown").isTrue();
        assertThat(OffsetDateTime.parse(items.get(0).path("acceptedAt").asString()))
                .as("newest first").isAfter(OffsetDateTime.parse(repaid.path("acceptedAt").asString()));
    }
}
