package zw.co.innbucks.loans.web;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import zw.co.innbucks.loans.controller.StaffLoanWriteOffController;
import zw.co.innbucks.loans.controller.StaffLoanController;
import zw.co.innbucks.loans.controller.StaffLoanMerchantController;
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
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.loan.CreditWorkbenchService;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanAgreement;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanJourneyService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanQuote;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanView;
import zw.co.innbucks.loans.core.voucher.VoucherCodes;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
            StaffLoanArrearsReportController.class);

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

    @Test
    void everyStaffLoanExampleHasTheShapeTheApiAnswersWith() {
        // A client codes against these fields: each portal loan, cash or voucher, is a StaffLoanResponse, and each
        // screen of the SuperApp, cash or voucher, a StaffLoanView or StaffLoanQuote. A field left out while empty is
        // left out of the example, never shown as null; every other field is there, in the record's order.
        List<JsonNode> voucherLoans = List.of(items(StaffLoanApiExamples.STAFF_LOANS).get(0),
                items(StaffLoanApiExamples.STAFF_LOANS_FLAGGED).get(0),
                JSON.readTree(StaffLoanApiExamples.STAFF_LOAN_DETAIL).path("data").path("loan"),
                data(StaffLoanApiExamples.CANCELLED));
        for (JsonNode loan : voucherLoans) {
            assertShape(loan, StaffLoanResponse.class);
        }
        for (JsonNode loan : cashLoans().values()) {
            assertShape(loan, StaffLoanResponse.class);
        }
        List<JsonNode> voucherViews = new ArrayList<>(List.of(
                data(StaffLoanApiExamples.HOME_LOAN).path("loan"), data(StaffLoanApiExamples.HOME_VOUCHER).path("loan"),
                data(StaffLoanApiExamples.ACCEPTED)));
        items(StaffLoanApiExamples.LOAN_HISTORY).forEach(voucherViews::add);
        for (JsonNode view : voucherViews) {
            assertShape(view, StaffLoanView.class);
        }
        for (JsonNode view : List.of(data(StaffLoanApiExamples.HOME_CASH_AWAITING).path("loan"),
                data(StaffLoanApiExamples.HOME_CASH_PAID).path("loan"), data(StaffLoanApiExamples.ACCEPTED_CASH))) {
            assertShape(view, StaffLoanView.class);
            assertThat(view.path("payoutMethod").asString()).isEqualTo("CASH");
        }
        assertShape(data(StaffLoanApiExamples.QUOTE), StaffLoanQuote.class);
        assertShape(data(StaffLoanApiExamples.QUOTE_CASH), StaffLoanQuote.class);

        // A voucher loan reads exactly as it did before cash payouts: no payout field at all, not even payoutMethod.
        List<JsonNode> voucher = new ArrayList<>(voucherLoans);
        voucher.addAll(voucherViews);
        voucher.add(data(StaffLoanApiExamples.QUOTE));
        for (JsonNode loan : voucher) {
            assertThat(names(loan)).as(loan.path("reference").asString())
                    .noneMatch(name -> name.startsWith("payout") || name.equals("bankLoanAccount"));
        }
    }

    /**
     * {@code example} is a {@code type} as Jackson writes it: every field that is always written is there, a field
     * written only when it has a value is there only with one, and they come in the record's order.
     */
    private static void assertShape(JsonNode example, Class<? extends Record> type) {
        List<String> present = names(example);
        List<String> expected = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            // On the accessor: JsonInclude cannot target a record component, so Java puts it on the field, the
            // accessor and the constructor's parameter.
            JsonInclude include = component.getAccessor().getAnnotation(JsonInclude.class);
            boolean optional = include != null && include.value() == JsonInclude.Include.NON_NULL;
            if (!optional) {
                expected.add(component.getName());
            } else if (present.contains(component.getName())) {
                assertThat(example.path(component.getName()).isNull()).as(type.getSimpleName() + "."
                        + component.getName() + " is never written as null").isFalse();
                expected.add(component.getName());
            }
        }
        assertThat(present).as(type.getSimpleName() + " " + example.path("reference").asString())
                .containsExactlyElementsOf(expected);
    }

    @Test
    void theCashLoanExamplesTellOneStory() {
        // SGL-2027-000245 is paid into Chipo Banda's wallet: whichever screen a reader starts from, it is the same
        // loan, the same wallet (her phone on the register), the same acceptance, and its steps happen in order.
        String wallet = null;
        for (JsonNode member : JSON.readTree(ApiExamples.STAFF_MEMBERS).path("data").path("items")) {
            if (member.path("employeeNumber").asString().equals("E1012")) {
                wallet = member.path("mobileNumber").asString();
            }
        }
        assertThat(wallet).isNotNull();
        String masked = MsisdnUtils.mask(wallet);
        String last4 = MsisdnUtils.lastFourDigits(wallet);
        Map<String, JsonNode> portal = cashLoans();
        JsonNode first = portal.get("refused");
        OffsetDateTime acceptedAt = OffsetDateTime.parse(first.path("acceptedAt").asString());
        for (Map.Entry<String, JsonNode> step : portal.entrySet()) {
            JsonNode loan = step.getValue();
            String as = step.getKey();
            assertThat(loan.path("id").asLong()).as(as).isEqualTo(245);
            assertThat(loan.path("reference").asString()).as(as).isEqualTo("SGL-2027-000245");
            assertThat(loan.path("employeeNumber").asString()).as(as).isEqualTo("E1012");
            assertThat(loan.path("msisdn").asString()).as(as).isEqualTo(masked);
            assertThat(loan.path("payoutAccount").asString()).as(as).isEqualTo(masked);
            assertThat(loan.path("payoutMethod").asString()).as(as).isEqualTo("CASH");
            assertThat(loan.path("payoutDestination").asString()).as(as).isEqualTo("VEENGU_WALLET");
            for (String field : List.of("acceptedAt", "amount", "dueDate", "offerId", "totalRepayable")) {
                assertThat(loan.path(field)).as(as + " " + field).isEqualTo(first.path(field));
            }
            assertThat(loan.path("payoutClaimedAt").isMissingNode()).as(as + ": nothing here is being sent").isTrue();
            String payout = loan.path("payoutStatus").asString();
            String status = loan.path("status").asString();
            assertThat(payout.equals("PAID")).as(as).isEqualTo(status.equals("DISBURSED"));
            assertThat(payout.equals("FAILED")).as(as).isEqualTo(status.equals("CANCELLED"));
            for (String later : List.of("payoutBankSeenAt", "payoutNotFoundAt", "payoutCheckedAt", "disbursedAt",
                    "payoutFailedAt")) {
                if (!loan.path(later).isNull() && !loan.path(later).isMissingNode()) {
                    assertThat(OffsetDateTime.parse(loan.path(later).asString())).as(as + " " + later)
                            .isAfter(acceptedAt);
                }
            }
            if (status.equals("DISBURSED")) {
                assertThat(loan.path("disbursementReference").asString()).as(as).isEqualTo("SGL-2027-000245");
                assertThat(OffsetDateTime.parse(loan.path("disbursedAt").asString())).as(as)
                        .isAfter(OffsetDateTime.parse(loan.path("payoutBankSeenAt").asString()));
            }
        }
        assertThat(portal.get("sent again").path("payoutLastAnswer"))
                .as("Pay out sends the refused payout again, its refusal kept")
                .isEqualTo(first.path("payoutLastAnswer"));
        JsonNode inDoubt = portal.get("in doubt");
        JsonNode notBooked = portal.get("never booked");
        OffsetDateTime notFoundAt = OffsetDateTime.parse(inDoubt.path("payoutNotFoundAt").asString());
        String from = notFoundAt.plusHours(24).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertThat(inDoubt.path("payoutMessage").asString()).contains("from " + from + " a SUPER_ADMIN");
        assertThat(notBooked.path("payoutNotFoundAt")).isEqualTo(inDoubt.path("payoutNotFoundAt"));
        assertThat(notBooked.path("cancelledAt")).isEqualTo(notBooked.path("payoutFailedAt"));
        assertThat(OffsetDateTime.parse(notBooked.path("payoutFailedAt").asString()))
                .as("never booked only once reported missing for 24 hours").isAfter(notFoundAt.plusHours(24));
        assertThat(OffsetDateTime.parse(notBooked.path("payoutCheckedAt").asString()))
                .as("and while the inquiry still says so: its last answer within twice the check interval (PT3M)")
                .isAfterOrEqualTo(OffsetDateTime.parse(notBooked.path("payoutFailedAt").asString()).minusMinutes(6));
        assertThat(JSON.readTree(StaffLoanApiExamples.PAYOUT_NOT_BOOKED_STALE).path("message").asString())
                .contains("check its status now, then record it within 6 minutes");
        assertThat(portal.get("checked").path("bankLoanAccount").asString()).as("never shown in full")
                .startsWith("****");
        for (JsonNode loan : portal.values()) {
            assertThat(loan.toString()).as("no phone number in full, in any answer kept").doesNotContain(wallet);
        }
        assertThat(JSON.readTree(StaffLoanApiExamples.PAYOUT_NOT_BOOKED_TOO_SOON).path("message").asString())
                .contains("only since " + notFoundAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .endsWith("it can be from " + from);
        assertThat(JSON.readTree(StaffLoanApiExamples.PAYOUT_NOT_BOOKED_SEEN).path("message").asString())
                .endsWith("(since " + portal.get("checked").path("payoutBankSeenAt").asString() + ")");

        // The SuperApp's screens of the same loan.
        LocalDate due = LocalDate.parse(first.path("dueDate").asString());
        JsonNode awaiting = data(StaffLoanApiExamples.HOME_CASH_AWAITING).path("loan");
        JsonNode paid = data(StaffLoanApiExamples.HOME_CASH_PAID).path("loan");
        for (JsonNode loan : List.of(awaiting, paid, data(StaffLoanApiExamples.ACCEPTED_CASH))) {
            assertThat(loan.path("reference").asString()).isEqualTo("SGL-2027-000245");
            assertThat(loan.path("payoutAccount").asString()).isEqualTo(masked);
            assertThat(loan.path("payoutMethod").asString()).isEqualTo("CASH");
            assertThat(loan.path("acceptedAt")).isEqualTo(first.path("acceptedAt"));
            assertThat(loan.path("repaymentDate")).isEqualTo(first.path("dueDate"));
            assertThat(loan.path("amount")).isEqualTo(first.path("amount"));
            assertThat(loan.path("merchantName").isNull() && loan.path("voucher").isNull()).isTrue();
            assertThat(loan.path("statusMessage").asString()).contains("InnBucks wallet ending " + last4);
        }
        assertThat(paid.path("statusMessage").asString()).endsWith("collected from your salary on "
                + DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH).format(due) + ".");
        JsonNode quote = data(StaffLoanApiExamples.QUOTE_CASH);
        assertThat(quote.path("payoutAccount").asString()).isEqualTo(masked);
        assertThat(quote.path("amount")).isEqualTo(first.path("amount"));
        assertThat(quote.path("repaymentDate")).isEqualTo(first.path("dueDate"));
        assertThat(quote.path("offerId")).isEqualTo(first.path("offerId"));
        assertThat(quote.path("redemption").asString()).endsWith("InnBucks wallet ending " + last4 + ".");
        JsonNode agreement = quote.path("agreement");
        assertThat(AuditService.sha256Hex(agreement.path("content").asString()))
                .isEqualTo(agreement.path("contentSha256").asString())
                .isEqualTo(StaffLoanApiExamples.CASH_AGREEMENT_SHA256);
        assertThat(agreement.path("content").asString()).contains("InnBucks wallet " + masked)
                .doesNotContainIgnoringCase("voucher");

        // Every refusal of it names it.
        for (String refusal : List.of(StaffLoanApiExamples.PAYOUT_SEND_CONFLICT, StaffLoanApiExamples.PAYOUT_SEND_PARTY,
                StaffLoanApiExamples.PAYOUT_QUEUE_FULL, StaffLoanApiExamples.PAYOUT_CHECK_SENDING,
                StaffLoanApiExamples.PAYOUT_CHECK_NOT_CHECKABLE, StaffLoanApiExamples.PAYOUT_CHECK_UNANSWERED,
                StaffLoanApiExamples.PAYOUT_CHECK_NOT_RECORDED, StaffLoanApiExamples.PAYOUT_NOT_BOOKED_STALE,
                StaffLoanApiExamples.PAYOUT_PAID_PARTY, StaffLoanApiExamples.PAYOUT_PAID_CONFLICT,
                StaffLoanApiExamples.PAYOUT_NOT_BOOKED_PARTY, StaffLoanApiExamples.CANCEL_CONFLICT_PAYOUT)) {
            assertThat(JSON.readTree(refusal).path("message").asString()).contains("SGL-2027-000245");
        }
        assertThat(JSON.readTree(StaffLoanApiExamples.NOT_FOUND_245).path("message").asString()).contains("245");
        assertThat(JSON.readTree(StaffLoanApiExamples.CANCEL_CONFLICT_PAYOUT).path("message").asString())
                .endsWith("/lending/v1/staff-loans/245/payout/not-booked");
    }

    /** The portal's view of SGL-2027-000245 at each step, in the order of its story. */
    private static Map<String, JsonNode> cashLoans() {
        Map<String, JsonNode> steps = new LinkedHashMap<>();
        steps.put("refused", items(StaffLoanApiExamples.STAFF_LOANS_REFUSED).get(0));
        steps.put("sent again", data(StaffLoanApiExamples.PAYOUT_REQUESTED));
        steps.put("in doubt", items(StaffLoanApiExamples.STAFF_LOANS_IN_DOUBT).get(0));
        steps.put("checked", data(StaffLoanApiExamples.PAYOUT_CHECKED));
        steps.put("confirmed paid", data(StaffLoanApiExamples.PAYOUT_CONFIRMED_PAID));
        steps.put("never booked", data(StaffLoanApiExamples.PAYOUT_NOT_BOOKED));
        return steps;
    }

    private static JsonNode data(String example) {
        return JSON.readTree(example).path("data");
    }

    private static JsonNode items(String example) {
        return data(example).path("items");
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
