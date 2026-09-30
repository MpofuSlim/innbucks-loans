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
import zw.co.innbucks.loans.controller.AuthController;
import zw.co.innbucks.loans.controller.CommissionGroupController;
import zw.co.innbucks.loans.controller.CreditReasonCodeController;
import zw.co.innbucks.loans.controller.CurrentUserController;
import zw.co.innbucks.loans.controller.DashboardController;
import zw.co.innbucks.loans.controller.DeductionBatchController;
import zw.co.innbucks.loans.controller.DeductionCancellationController;
import zw.co.innbucks.loans.controller.HeldBookingController;
import zw.co.innbucks.loans.controller.InstrumentTemplateController;
import zw.co.innbucks.loans.controller.LoanApplicationDraftController;
import zw.co.innbucks.loans.controller.LoanController;
import zw.co.innbucks.loans.controller.LoanDocumentController;
import zw.co.innbucks.loans.controller.MerchantController;
import zw.co.innbucks.loans.controller.PayslipReviewController;
import zw.co.innbucks.loans.controller.ReportController;
import zw.co.innbucks.loans.controller.SignedInstrumentController;
import zw.co.innbucks.loans.controller.UserController;
import zw.co.innbucks.loans.core.audit.AuditService;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
            CreditReasonCodeController.class, CurrentUserController.class, DashboardController.class,
            DeductionBatchController.class, DeductionCancellationController.class, HeldBookingController.class,
            InstrumentTemplateController.class, LoanApplicationDraftController.class, LoanController.class,
            LoanDocumentController.class, PayslipReviewController.class, MerchantController.class,
            ReportController.class, SignedInstrumentController.class, UserController.class);

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

    static Stream<Class<?>> controllers() {
        return CONTROLLERS.stream();
    }
}
