package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.borrower.TestAssertionRequest;
import zw.co.innbucks.loans.core.borrower.TestAssertionSigner;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.BorrowerApiExamples;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Staging's stand-in for the middleware ({@link TestAssertionSigner}). The controller exists only where test assertions
 * are switched on, so everywhere else the path is a plain 404 whatever is sent to it, the same as any path that was
 * never there. Where it does exist, the api key is checked before the body is validated: a caller without the key
 * learns nothing about what the body should hold.
 */
@Tag(name = "Borrower sign-in (SuperApp)")
@RestController
@RequestMapping(ApiPaths.BASE + "/auth")
@RequiredArgsConstructor
@SecurityRequirements
@Slf4j
@ConditionalOnProperty(prefix = "loans.borrower.test-assertions", name = "enabled", havingValue = "true")
public class TestAssertionController {

    static final String API_KEY_HEADER = "X-Api-Key";

    private final TestAssertionSigner signer;
    private final Validator validator;

    @Operation(summary = "Staging only: sign a test assertion",
            description = "Stands in for the middleware until it signs assertions itself: returns an assertion in"
                    + " exactly its shape, for any phone, good for two minutes and one use. Trade it at"
                    + " POST /auth/exchange to sign in, or send it to approve a loan. methods is how the user"
                    + " authenticated (pin, fpt or face; pin when left out). Needs the server's test api key in"
                    + " X-Api-Key. Exists only where test assertions are on, which is nowhere but staging.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.TEST_ASSERTION))),
            @ApiResponse(responseCode = "400", description = "No phone sent",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "phone": "phone is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "Wrong or missing api key",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.TEST_ASSERTION_KEY_REFUSED)))
    })
    @PostMapping("/test-assertions")
    public ResponseEntity<ApiResult<?>> sign(
            @Parameter(in = ParameterIn.HEADER, name = API_KEY_HEADER, description = "The server's test api key")
            @RequestHeader(value = API_KEY_HEADER, required = false) String apiKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(BorrowerApiExamples.TEST_ASSERTION_REQUEST)))
            @RequestBody(required = false) TestAssertionRequest request) {
        if (!signer.acceptsKey(apiKey)) {
            log.warn("Test assertion refused: wrong or missing api key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.error("UNAUTHORIZED", "Invalid or missing api key"));
        }
        TestAssertionRequest body = request == null ? new TestAssertionRequest() : request;
        Map<String, String> fields = new TreeMap<>();
        for (ConstraintViolation<TestAssertionRequest> violation : validator.validate(body)) {
            fields.putIfAbsent(field(violation.getPropertyPath()), violation.getMessage());
        }
        if (!fields.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResult.error("VALIDATION_ERROR", "Request validation failed",
                    fields));
        }
        log.warn("Signed a TEST borrower assertion for {}", MsisdnUtils.mask(body.getPhone()));
        return ResponseEntity.ok(ApiResult.ok(signer.sign(body.getPhone().strip(), body.getMethods())));
    }

    /** The request field a violation is on: {@code methods} for a bad element of it, not the element's path. */
    private static String field(Path path) {
        Iterator<Path.Node> nodes = path.iterator();
        return nodes.hasNext() ? nodes.next().getName() : "request";
    }
}
