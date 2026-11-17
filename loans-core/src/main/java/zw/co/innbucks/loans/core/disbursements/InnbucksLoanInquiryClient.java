package zw.co.innbucks.loans.core.disbursements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The bank's inquiry, {@code GET /bank/api/loan/inquiry/{participantReference}}, for the Staff Grocery Loan's cash
 * payout. Read-only, and separate from {@link InnbucksDisbursementService} on purpose: what holds it can never book.
 *
 * <p>The answer is read as text first and only then parsed, as a tree: a field the bank adds, a date in a shape nobody
 * modelled, or a number written as a string cannot fail the whole answer (the SSB inquiry binds a typed model, where a
 * non-ISO {@code disbursementDate} turns every answer into an error). Only the bank's own envelope decides anything:
 * responseCode 000 in a 2xx is FOUND, 004 (in a 2xx, or in a 404's body) is NOT_FOUND. A bare 404, an HTML page or a
 * 5xx says nothing about the loan and is UNANSWERED, as is any failure; this never throws. Only a 401 is replayed, once,
 * after a fresh login.
 *
 * <p>No real inquiry answer has ever been recorded: the field names are the repo's existing model
 * ({@link LoanDisbursementStatusResponse}). Replace the contract stubs with recorded answers once the bank sends them.
 */
@Slf4j
@Component
public class InnbucksLoanInquiryClient implements InnbucksLoanInquiry {

    /** References are letters, digits and dashes (SGL-2026-000143): nothing that could leave the path segment. */
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9-]{1,64}");
    /** The longest loan account kept: the size of {@code staff_loans.bank_loan_account}. */
    static final int MAX_LOAN_ACCOUNT_CHARS = 64;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTemplate restTemplate;
    private final InnbucksParameters parameters;
    private final InnbucksAuthService authService;

    public InnbucksLoanInquiryClient(RestTemplate restTemplate, InnbucksParameters parameters,
                                     InnbucksAuthService authService) {
        this.restTemplate = restTemplate;
        this.parameters = parameters;
        this.authService = authService;
    }

    @Override
    public InquiryAnswer inquire(String participantReference) {
        if (participantReference == null || !REFERENCE.matcher(participantReference).matches()) {
            log.error("InnBucks inquiry not made: {} is not a reference", participantReference);
            return InquiryAnswer.unanswered(0, null, "Not asked: not a reference");
        }
        String url = parameters.getLoanInquiryEndpoint().replace("{participantReference}", participantReference);
        String token = null;
        try {
            token = authService.getAccessToken();
            ResponseEntity<String> answer;
            try {
                answer = get(url, participantReference, token);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() != HttpStatus.UNAUTHORIZED) {
                    throw e;
                }
                log.warn("InnBucks refused the token on the inquiry for {}; logging in again and asking once more",
                        participantReference);
                answer = get(url, participantReference, authService.refreshAfterRejection(token));
            }
            return read(participantReference, answer.getStatusCode().value(), answer.getBody(), answer.getHeaders());
        } catch (RestClientResponseException e) {
            return read(participantReference, e.getStatusCode().value(), e.getResponseBodyAsString(),
                    e.getResponseHeaders());
        } catch (RuntimeException e) {
            log.warn("InnBucks inquiry for {} went unanswered ({}{})", participantReference,
                    token == null ? "login failed: " : "", e.getClass().getSimpleName());
            return InquiryAnswer.unanswered(0, null, (token == null ? "InnBucks login failed: " : "No answer: ")
                    + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()));
        }
    }

    private ResponseEntity<String> get(String url, String reference, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(token);
        headers.add(InnbucksHeaders.X_API_KEY, parameters.getApiKey());
        headers.add(InnbucksHeaders.X_TRACE_ID, reference);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private InquiryAnswer read(String reference, int httpStatus, String body, HttpHeaders headers) {
        String raw = BankAnswers.kept(body, headers);
        JsonNode root = parse(body);
        String responseCode = root == null ? null : text(root.path("responseCode"));
        boolean ok = httpStatus >= 200 && httpStatus < 300;
        InquiryAnswer answer;
        if (ok && InquiryAnswer.FOUND_CODE.equals(responseCode)) {
            JsonNode details = root.path("additionalData").path("loanDetails");
            answer = new InquiryAnswer(InquiryAnswer.Kind.FOUND, responseCode, text(details.path("status")),
                    bankLoanAccount(reference, text(details.path("loanAccountNumber"))),
                    firstOf(text(details.path("participantReference")), text(root.path("participantReference"))),
                    firstOf(decimal(details.path("amount")), decimal(root.path("amount"))),
                    decimal(details.path("interestRate")), text(details.path("msisdn")),
                    text(details.path("settlementAccount")), raw, httpStatus);
        } else if ((ok || httpStatus == HttpStatus.NOT_FOUND.value())
                && InquiryAnswer.NOT_FOUND_CODE.equals(responseCode)) {
            answer = new InquiryAnswer(InquiryAnswer.Kind.NOT_FOUND, responseCode, null, null, null, null, null, null,
                    null, raw, httpStatus);
        } else {
            answer = InquiryAnswer.unanswered(httpStatus, responseCode,
                    raw != null ? raw : "InnBucks answered HTTP " + httpStatus + " with no body");
        }
        // The kind and the codes only: the answer names the borrower.
        log.info("InnBucks inquiry for {} answered HTTP {}: {} (responseCode {}, status {})", reference, httpStatus,
                answer.kind(), responseCode, answer.bankStatus());
        return answer;
    }

    /** The answer as a JSON object, or null when it is not one (an HTML page, an empty body). */
    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** A scalar as text, stripped; null for a missing, null, empty or composite value. */
    private static String text(JsonNode node) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }

    /** A number, written as one or as a string; null when it is neither. */
    private static BigDecimal decimal(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String bankLoanAccount(String reference, String account) {
        if (account != null && account.length() > MAX_LOAN_ACCOUNT_CHARS) {
            log.warn("InnBucks inquiry for {} named a loan account of {} characters; not kept", reference,
                    account.length());
            return null;
        }
        return account;
    }

    private static <T> T firstOf(T first, T second) {
        return first != null ? first : second;
    }
}
