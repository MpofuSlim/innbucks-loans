package zw.co.reikan.loans.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static zw.co.reikan.loans.core.config.HttpLogRedactor.MASK;

/**
 * The redaction every Ndasenda / InnBucks call passes through before it reaches the log.
 * The bodies below are the shapes this service actually sends and receives (the Ndasenda
 * password grant and deduction batch, the InnBucks login, token and loan application).
 */
class HttpLogRedactorTest {

    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJsb2FucyJ9.c2lnbmF0dXJlLXZhbHVl";
    private static final String BASE64_IMAGE = "iVBORw0KGgoAAAANSUhEUgAA" + "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo".repeat(12);

    private static String body(String json) {
        return HttpLogRedactor.redactBody(json.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("credential headers are masked whatever their case; ordinary headers are kept")
    void redactsCredentialHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("bearer-secret-1");
        headers.add("X-Api-Key", "api-key-secret-2");
        headers.add("Proxy-Authorization", "Basic cHJveHk6c2VjcmV0");
        headers.add("Cookie", "SESSION=cookie-secret-3");
        headers.add("Set-Cookie", "SESSION=cookie-secret-4; HttpOnly");
        headers.add("X-Auth-Token", "token-secret-5");
        headers.add("X-Client-Secret", "client-secret-6");
        headers.add("X-Trace-Id", "000000042");

        String redacted = HttpLogRedactor.redactHeaders(headers);

        assertThat(redacted)
                .doesNotContain("bearer-secret-1", "api-key-secret-2", "cHJveHk6c2VjcmV0", "cookie-secret-3",
                        "cookie-secret-4", "token-secret-5", "client-secret-6")
                .contains("Authorization:\"" + MASK + "\"")
                .contains("X-Api-Key:\"" + MASK + "\"")
                .contains("application/json")
                .contains("X-Trace-Id:\"000000042\"");
    }

    @Test
    @DisplayName("header names match in any case: x-api-key, X-API-KEY, authorization")
    void headerNamesAreCaseInsensitive() {
        assertThat(HttpLogRedactor.isSensitiveHeader("x-api-key")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveHeader("X-API-KEY")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveHeader("authorization")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveHeader("SET-COOKIE")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveHeader("X-Password-Hint")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveHeader("Content-Type")).isFalse();
        assertThat(HttpLogRedactor.isSensitiveHeader("X-Trace-Id")).isFalse();
    }

    @Test
    @DisplayName("an unknown header carrying a bearer credential or JWT is still scrubbed")
    void scrubsCredentialsInOrdinaryHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Debug", "Bearer leaked-bearer-7 and " + JWT);

        String redacted = HttpLogRedactor.redactHeaders(headers);

        assertThat(redacted).doesNotContain("leaked-bearer-7", JWT).contains("Bearer " + MASK);
    }

    @Test
    @DisplayName("the InnBucks login and its token answer lose the password and the accessToken")
    void redactsInnbucksLoginAndToken() {
        String request = body("{\"username\":\"svc-loans\",\"password\":\"innbucks-pw\"}");
        String response = body("{\"responseCode\":\"00\",\"responseDescription\":\"Success\","
                + "\"accessToken\":\"" + JWT + "\",\"accessExpiry\":\"2026-09-29T12:00:00\"}");

        assertThat(request).doesNotContain("innbucks-pw").contains("\"password\":\"***\"").contains("svc-loans");
        assertThat(response).doesNotContain(JWT).contains("\"accessToken\":\"***\"").contains("\"responseCode\":\"00\"");
    }

    @Test
    @DisplayName("nested JSON: the Ndasenda batch's security token and every deduction's national ID")
    void redactsNestedJson() {
        String redacted = body("{\"securityToken\":\"sec-token-8\",\"deductionCode\":\"LND01\","
                + "\"deductions\":[{\"reference\":\"000000042\",\"idNumber\":\"63-123456A63\",\"ecNumber\":\"1234567A\"},"
                + "{\"reference\":\"000000043\",\"idNumber\":\"08-765432B08\"}]}");

        assertThat(redacted)
                .doesNotContain("sec-token-8", "63-123456A63", "08-765432B08")
                .contains("\"securityToken\":\"***\"", "\"idNumber\":\"***\"", "\"deductionCode\":\"LND01\"",
                        "\"reference\":\"000000042\"", "\"reference\":\"000000043\"");
    }

    @Test
    @DisplayName("the loan application loses national IDs, the next of kin's ID and the base64 documents")
    void redactsLoanApplicationPii() {
        String redacted = body("{\"ecnumber\":\"1234567A\",\"amount\":500,\"nationalId\":\"63-123456A63\","
                + "\"nextOfKinIdNumber\":\"08-111111C08\",\"nextOfKin\":{\"firstName\":\"Rudo\",\"nationalId\":\"08-222222D08\"},"
                + "\"national_id\":\"08-333333E08\",\"nationalIdNumber\":\"08-444444F08\","
                + "\"signatureData\":\"" + BASE64_IMAGE + "\",\"nationalIdPicture\":\"" + BASE64_IMAGE + "\","
                + "\"payslipPicture\":\"" + BASE64_IMAGE + "\",\"witness\":{\"fullName\":\"T. Moyo\",\"signature\":\"sig-9\"}}");

        assertThat(redacted)
                .doesNotContain("63-123456A63", "08-111111C08", "08-222222D08", "08-333333E08", "08-444444F08",
                        "iVBORw0KGgo", "sig-9")
                .contains("\"ecnumber\":\"1234567A\"", "\"amount\":500", "\"firstName\":\"Rudo\"", "T. Moyo");
    }

    @Test
    @DisplayName("keys match in any case and spelling: ACCESS_TOKEN, Refresh-Token, SecurityCode, API_KEY, pin")
    void keysAreCaseAndSeparatorInsensitive() {
        String redacted = body("{\"ACCESS_TOKEN\":\"s1\",\"Refresh-Token\":\"s2\",\"id_token\":\"s3\","
                + "\"SecurityCode\":\"s4\",\"security_code\":\"s5\",\"API_KEY\":\"s6\",\"apiKey\":\"s7\","
                + "\"Client_Secret\":\"s8\",\"PIN\":\"s9\",\"PassWord\":\"s10\",\"secret\":{\"nested\":\"s11\"},"
                + "\"token\":12345,\"securityToken\":\"s12\",\"responseCode\":\"00\"}");

        for (int i = 1; i <= 12; i++) {
            assertThat(redacted).doesNotContain("\"s" + i + "\"");
        }
        assertThat(redacted).doesNotContain("12345").contains("\"responseCode\":\"00\"");
    }

    @Test
    @DisplayName("a credential inside a free-text JSON value (an echoed URL, an error message) is masked")
    void redactsCredentialsInsideStringValues() {
        String redacted = body("{\"error\":\"invalid_request\",\"detail\":\"retry https://x/cb?access_token=at-25&state=ok\","
                + "\"message\":\"password: pw-26 rejected\"}");

        assertThat(redacted).doesNotContain("at-25", "pw-26")
                .contains("access_token=***", "state=ok", "\"error\":\"invalid_request\"");
    }

    @Test
    @DisplayName("a sensitive key holding null stays null: nothing to hide, and the absence is diagnostic")
    void nullStaysNull() {
        assertThat(body("{\"password\":null}")).isEqualTo("{\"password\":null}");
    }

    @Test
    @DisplayName("a long base64 run under an innocent key is still masked")
    void longBase64UnderAnyKey() {
        String redacted = body("{\"note\":\"ok\",\"blob\":\"" + BASE64_IMAGE + "\"}");

        assertThat(redacted).doesNotContain("iVBORw0KGgo").contains("\"blob\":\"***\"", "\"note\":\"ok\"");
    }

    @Test
    @DisplayName("the Ndasenda password grant: form fields redacted by the same key rules")
    void redactsFormBody() {
        byte[] form = ("grant_type=password&username=svc-ndasenda&password=p%40ss-10&security_code=4242"
                + "&client_secret=cs-11&Access_Token=at-12").getBytes(StandardCharsets.UTF_8);

        String redacted = HttpLogRedactor.redactBody(form,
                MediaType.parseMediaType("application/x-www-form-urlencoded;charset=UTF-8"));

        assertThat(redacted)
                .doesNotContain("p%40ss-10", "4242", "cs-11", "at-12")
                .contains("grant_type=password", "username=svc-ndasenda", "password=***", "security_code=***",
                        "client_secret=***", "Access_Token=***");
    }

    @Test
    @DisplayName("a form body sent without a content type is still redacted")
    void redactsFormBodyWithoutContentType() {
        String redacted = HttpLogRedactor.redactBody(
                "username=svc&password=pw-13".getBytes(StandardCharsets.UTF_8), null);

        assertThat(redacted).doesNotContain("pw-13").contains("password=***", "username=svc");
    }

    @Test
    @DisplayName("an unparseable body falls back to the regex scrub, never to the raw text")
    void redactsUnparseableBody() {
        String truncatedJson = "{\"username\":\"svc\",\"accessToken\":\"at-14-cut-off";
        String prose = "invalid_grant: password=pw-15, token: tk-16; Authorization: Bearer br-17 " + JWT;
        String xml = "<error><code>401</code><Password>pw-18</Password><apiKey attr=\"x\">ak-19</apiKey></error>";

        assertThat(HttpLogRedactor.redactBody(truncatedJson.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON))
                .doesNotContain("at-14").contains("\"accessToken\":\"***\"").contains("\"username\":\"svc\"");
        assertThat(HttpLogRedactor.redactBody(prose.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_PLAIN))
                .doesNotContain("pw-15", "tk-16", "br-17", JWT).contains("invalid_grant");
        assertThat(HttpLogRedactor.redactBody(xml.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_XML))
                .doesNotContain("pw-18", "ak-19").contains("<code>401</code>", "<Password>***</Password>");
    }

    @Test
    @DisplayName("an unparseable body is truncated, and still redacted where it is kept")
    void truncatesLongBodies() {
        String text = "password=pw-20 " + "status ok; ".repeat(HttpLogRedactor.MAX_LOGGED_BODY_CHARS);

        String redacted = HttpLogRedactor.redactBody(text.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_PLAIN);

        assertThat(redacted).doesNotContain("pw-20").startsWith("password=***").contains("truncated");
        assertThat(redacted.length()).isLessThan(HttpLogRedactor.MAX_LOGGED_BODY_CHARS + 100);
    }

    @Test
    @DisplayName("binary bodies are described, never printed")
    void omitsBinaryBodies() {
        String redacted = HttpLogRedactor.redactBody(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, MediaType.IMAGE_PNG);

        assertThat(redacted).isEqualTo("<4 bytes of image/png omitted>");
    }

    @Test
    @DisplayName("URIs lose user-info, the fragment and the values of sensitive query parameters")
    void redactsUri() {
        URI uri = URI.create("https://svc:uri-pw-21@sandbox.deductions.ndasenda.co.zw:8443/connect/token"
                + "?client_id=loans&access_token=at-22&api_key=ak-23&from=2026-09-01#frag-24");

        String redacted = HttpLogRedactor.redactUri(uri);

        assertThat(redacted)
                .isEqualTo("https://sandbox.deductions.ndasenda.co.zw:8443/connect/token"
                        + "?client_id=loans&access_token=***&api_key=***&from=2026-09-01");
    }

    @Test
    @DisplayName("a JWT in the path or under an innocent query parameter is scrubbed too; empty pairs survive")
    void scrubsTokensAnywhereInTheUri() {
        String redacted = HttpLogRedactor.redactUri(
                URI.create("https://host/callback/" + JWT + "?&state=" + JWT + "&&page=2"));

        assertThat(redacted).doesNotContain(JWT).isEqualTo("https://host/callback/***?&state=***&&page=2");
    }

    @Test
    @DisplayName("an ordinary endpoint URI is logged as is")
    void keepsOrdinaryUri() {
        String uri = "https://staging.innbucks.co.zw/bank/api/loan/inquiry/000000042";

        assertThat(HttpLogRedactor.redactUri(URI.create(uri))).isEqualTo(uri);
    }

    @Test
    @DisplayName("sensitive key recognition covers the credential and PII vocabulary, not ordinary fields")
    void sensitiveKeyVocabulary() {
        assertThat(HttpLogRedactor.isSensitiveKey("accessToken")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveKey("access_token")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveKey("refresh_token")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveKey("securityToken")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveKey("securityCode")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveKey("nationalIdNumber")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveKey("nextOfKinIdNumber")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveKey("payslipPicture")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveKey("pin")).isTrue();
        assertThat(HttpLogRedactor.isSensitiveKey("username")).isFalse();
        assertThat(HttpLogRedactor.isSensitiveKey("reference")).isFalse();
        assertThat(HttpLogRedactor.isSensitiveKey("amountInCents")).isFalse();
        assertThat(HttpLogRedactor.isSensitiveKey("shipping")).isFalse();
        assertThat(HttpLogRedactor.isSensitiveKey(null)).isFalse();
    }
}
