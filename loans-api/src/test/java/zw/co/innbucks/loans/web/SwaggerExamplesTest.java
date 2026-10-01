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
import zw.co.innbucks.loans.controller.StaffOfferController;
import zw.co.innbucks.loans.controller.StaffRegisterController;
import zw.co.innbucks.loans.controller.StaffRegisterReconciliationController;
import zw.co.innbucks.loans.controller.UserController;
import zw.co.innbucks.loans.controller.CheckpointController;
import zw.co.innbucks.loans.controller.CreditAuthorityController;
import zw.co.innbucks.loans.controller.WorkQueueController;
import zw.co.innbucks.loans.controller.WorkflowStageController;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.loan.CreditWorkbenchService;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.notice.LoanNotice;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
            StaffRegisterController.class, StaffRegisterReconciliationController.class, StaffOfferController.class);

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

    static Stream<Class<?>> controllers() {
        return CONTROLLERS.stream();
    }
}
