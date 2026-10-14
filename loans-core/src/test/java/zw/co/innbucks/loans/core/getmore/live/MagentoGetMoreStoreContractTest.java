package zw.co.innbucks.loans.core.getmore.live;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponSpec;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponUpdate;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemoQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreException;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreNotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.getmore.GetMoreOrderQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMorePage;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;
import zw.co.innbucks.loans.core.getmore.GetMoreStoreConfig;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException.Reason;
import zw.co.innbucks.loans.core.testsupport.TestOutboundHttp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link MagentoGetMoreStore}, GetMore's Magento 2.4 REST surface: each call's wire shape (method,
 * path, every query parameter including the {@code fields} projection, the strict JSON body, the exact OAuth 1.0a
 * {@code Authorization} header, {@code Accept}, no {@code traceparent}), every response shape, how each failure is
 * classified (and that no write is ever retried), and the guard rails that keep a call off the network.
 *
 * <p><b>What is and is not observed.</b> Stubs are Magento Open Source 2.4 standard shapes read from magento/magento2
 * source (paths cited); NOT recorded from GetMore, whose store was not reachable when this was written:
 * <ul>
 *   <li>error envelope and status mapping: {@code lib/internal/Magento/Framework/Webapi/Rest/Response.php:83-117},
 *       {@code Webapi/ErrorProcessor.php:119-240}; the 401 ACL message
 *       {@code Webapi/Controller/Rest/RequestValidator.php:104-115};</li>
 *   <li>generate: {@code SalesRule/Model/Service/CouponManagementService.php:101-165} (codes only, an unknown rule
 *       wrapped as a 400); deleteByCodes {@code :188-228} and
 *       {@code SalesRule/Api/Data/CouponMassDeleteResultInterface.php:19-44};</li>
 *   <li>coupons: {@code SalesRule/Api/Data/CouponInterface.php:16-185}, {@code SalesRule/Model/CouponRepository.php};
 *       the rule: {@code SalesRule/Api/Data/RuleInterface.php};</li>
 *   <li>orders: {@code Sales/Api/Data/OrderInterface.php} (discount signs per {@code Quote/Discount.php:239-240},
 *       {@code Order/Invoice.php:659-660}, {@code Creditmemo/RefundOperation.php:98-99}, {@code Order.php:1387-1391};
 *       {@code store_name} per {@code ResourceModel/Order.php:140-150}); credit memos:
 *       {@code Sales/Api/Data/CreditmemoInterface.php};</li>
 *   <li>a null field omitted from REST: {@code Framework/Reflection/DataObjectProcessor.php:88-90};</li>
 *   <li>an empty search under the {@code fields} projection, {@code {"items":null,"total_count":0}}
 *       ({@code coupon-search-empty.json}, for orders and credit memos too): the output of
 *       {@code Framework/Webapi/Rest/Response/FieldsFilter.php} {@code applyFilter}, whose result starts null and is
 *       never filled for an empty list (run on 2.4-develop's code with {@code COUPON_FIELDS}).</li>
 * </ul>
 * Amounts are shown as DECIMAL(12,4) renders ({@code -45.5000}); PHP may render the same value {@code -45.5}, which
 * reads the same.
 *
 * <p>Pure JUnit + WireMock, no Spring context; the client is built exactly as production builds it
 * ({@link GetMoreStoreConfig#getMoreRestClient}) on the shared test pool, with a fixed clock and nonce. The signer
 * signs as {@code https://qa.getmore.syncrony.com/rest/V1} ({@code signature-base-url}), so the generate call's header
 * is the spec's worked example 1, byte for byte.
 */
@ExtendWith(OutputCaptureExtension.class)
class MagentoGetMoreStoreContractTest {

    private static final String BASE = "/rest/V1";
    private static final String CODE = "INN-K7Q2M3XW-7KQ2M9XWD4RT8B1C";
    private static final String RANDOM_PART = "7KQ2M9XWD4RT8B1C";
    private static final String PREFIX = "INN-K7Q2M3XW-";
    private static final String EXAMPLE_1_HEADER = "OAuth oauth_consumer_key=\"ck0123456789abcdefghijklmnopqrst\", "
            + "oauth_nonce=\"n0n4e0123456789abcdefghijklmnopq\", "
            + "oauth_signature=\"j3EDqMXSXCI0%2BGKWGIyLd140drbngWcEqeNbkPA02Mw%3D\", "
            + "oauth_signature_method=\"HMAC-SHA256\", oauth_timestamp=\"1760430600\", "
            + "oauth_token=\"tk0123456789abcdefghijklmnopqrst\", oauth_version=\"1.0\"";

    private static WireMockServer wireMock;
    private SimpleMeterRegistry meters;
    private GetMoreProperties properties;
    private MagentoGetMoreStore store;

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
    void setUp() {
        wireMock.resetAll();
        meters = new SimpleMeterRegistry();
        properties = properties("http://localhost:" + wireMock.port() + BASE);
        store = store(properties);
    }

    private static GetMoreProperties properties(String baseUrl) {
        GetMoreProperties p = new GetMoreProperties();
        GetMoreProperties.Live live = p.getLive();
        live.setBaseUrl(baseUrl);
        live.setSignatureBaseUrl("https://qa.getmore.syncrony.com/rest/V1");
        live.setConsumerKey(MagentoOAuth1SignerTest.CONSUMER_KEY);
        live.setConsumerSecret(MagentoOAuth1SignerTest.CONSUMER_SECRET);
        live.setAccessToken(MagentoOAuth1SignerTest.TOKEN);
        live.setAccessTokenSecret(MagentoOAuth1SignerTest.TOKEN_SECRET);
        live.setConnectTimeoutMs(500);
        live.setReadTimeoutMs(1000);
        return p;
    }

    private MagentoGetMoreStore store(GetMoreProperties p) {
        // Built exactly as production builds it: GetMoreStoreConfig's pooled client, redirects off.
        RestClient restClient = GetMoreStoreConfig.getMoreRestClient(p, TestOutboundHttp.POOL);
        return new MagentoGetMoreStore(restClient, p.getLive(), MagentoOAuth1SignerTest.fixedSigner(p.getLive()),
                meters);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static String fixture(String name) {
        try (InputStream in = MagentoGetMoreStoreContractTest.class.getResourceAsStream("/getmore/magento/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json; charset=utf-8")
                .withBody(body);
    }

    private static ResponseDefinitionBuilder html(int status) {
        return aResponse().withStatus(status).withHeader("Content-Type", "text/html")
                .withBody("<html><body><h1>Request blocked</h1></body></html>");
    }

    private void stub(MappingBuilder mapping) {
        wireMock.stubFor(mapping);
    }

    /** The one request a call made: its Authorization is the signature of exactly the URL that reached the wire. */
    private LoggedRequest onlyRequest(String method) {
        List<LoggedRequest> requests = wireMock.findAll(anyRequestedFor(anyUrl()));
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.get(0);
        assertThat(request.getMethod().getName()).isEqualTo(method);
        String signedForWhatArrived = MagentoOAuth1SignerTest.fixedSigner(properties.getLive())
                .authorization(method, URI.create("http://localhost:" + wireMock.port() + request.getUrl()));
        assertThat(request.getHeader("Authorization")).isEqualTo(signedForWhatArrived);
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
        assertThat(request.containsHeader("traceparent")).isFalse();
        return request;
    }

    /**
     * WireMock journals a request on its own thread, and for a fault only after it has broken the connection: wait for
     * the first, give a retry (were one coming) time to land, then count.
     */
    private static void assertSentExactlyOnce(RequestPatternBuilder request) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try {
            while (wireMock.findAll(request).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        wireMock.verify(1, request);
    }

    private static <T extends Throwable> Consumer<T> noCode() {
        return e -> assertThat(e.getMessage()).doesNotContain(CODE, RANDOM_PART);
    }

    private static void assertUnavailable(Throwable thrown, Reason reason) {
        assertThat(thrown).isInstanceOf(GetMoreUnavailableException.class);
        assertThat(((GetMoreUnavailableException) thrown).reason()).isEqualTo(reason);
    }

    private double counted(String operation, String outcome) {
        var counter = meters.find(MagentoGetMoreStore.METRIC).tag("operation", operation).tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private Throwable generateFailure() {
        return catchFailure(() -> store.generateCoupons(GetMoreCouponSpec.single(43, PREFIX, 16)));
    }

    private static Throwable catchFailure(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected the call to fail");
    }

    private static final RequestPatternBuilder GENERATE = postRequestedFor(urlEqualTo(BASE + "/coupons/generate"));

    // ---- generate ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("generate: POST {couponSpec} strictly, signed as worked example 1; answers the codes only")
    void generateHappy() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(json(200, fixture("generate-200.json"))));

        List<String> codes = store.generateCoupons(GetMoreCouponSpec.single(43, PREFIX, 16));

        assertThat(codes).containsExactly(CODE);
        LoggedRequest request = onlyRequest("POST");
        assertThat(request.getHeader("Authorization")).isEqualTo(EXAMPLE_1_HEADER);
        wireMock.verify(postRequestedFor(urlEqualTo(BASE + "/coupons/generate"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"couponSpec\":{\"rule_id\":43,\"format\":\"alphanum\","
                        + "\"quantity\":1,\"length\":16,\"prefix\":\"INN-K7Q2M3XW-\"}}"))
                .withoutHeader("traceparent")
                .withoutHeader("tracestate"));
        assertThat(store.mode()).isEqualTo(GetMoreMode.LIVE);
        assertThat(counted("generate", "ok")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("generate, unknown rule: Magento's 400 template rendered, Rejected INVALID, sent once")
    void generateUnknownRule400Rendered() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(json(400,
                fixture("generate-400-unknown-rule.json"))));

        Throwable thrown = generateFailure();

        assertThat(thrown).isInstanceOfSatisfying(GetMoreRejectedException.class, e -> {
            assertThat(e.reason()).isEqualTo(GetMoreRejectedException.Reason.INVALID);
            assertThat(e.httpStatus()).isEqualTo(400);
            assertThat(e.operation()).isEqualTo(Operation.GENERATE);
            assertThat(e.renderedMessage())
                    .isEqualTo("Error occurred when generating coupons: No such entity with rule_id = 43");
        });
        assertSentExactlyOnce(GENERATE);
        assertThat(counted("generate", "rejected")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("generate, invalid spec: 'One or more input exceptions' with its nested errors, Rejected INVALID")
    void generateInputException400() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(json(400,
                "{\"message\":\"One or more input exceptions have occurred.\",\"errors\":[{\"message\":"
                        + "\"\\\"%fieldName\\\" is required. Enter and try again.\",\"parameters\":"
                        + "{\"fieldName\":\"quantity\"}}]}")));

        Throwable thrown = catchFailure(() -> store.generateCoupons(new GetMoreCouponSpec(43, 0, 16,
                zw.co.innbucks.loans.core.getmore.CouponFormat.ALPHANUM, PREFIX)));

        assertThat(thrown).isInstanceOfSatisfying(GetMoreRejectedException.class, e -> assertThat(e.renderedMessage())
                .isEqualTo("One or more input exceptions have occurred. (\"quantity\" is required. Enter and try "
                        + "again.)"));
        assertSentExactlyOnce(GENERATE);
    }

    @Test
    @DisplayName("generate, 401 (the ACL message): Unavailable AUTH, an ERROR naming the GETMORE_* keys, never replayed")
    void generate401Auth(CapturedOutput output) {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(json(401, fixture("error-401-acl.json"))));

        Throwable thrown = generateFailure();

        assertUnavailable(thrown, Reason.AUTH);
        assertThat(thrown).hasMessageContaining("The consumer isn't authorized to access Magento_SalesRule::quote.");
        assertSentExactlyOnce(GENERATE);
        assertThat(output.getOut()).contains("ERROR", "GETMORE_CONSUMER_KEY", "GETMORE_ACCESS_TOKEN_SECRET")
                .doesNotContain(MagentoOAuth1SignerTest.CONSUMER_SECRET, MagentoOAuth1SignerTest.TOKEN_SECRET,
                        "oauth_signature=");
    }

    @Test
    @DisplayName("generate, 429: Unavailable THROTTLED, not retried")
    void generate429() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(aResponse().withStatus(429)
                .withBody("Too Many Requests")));

        assertUnavailable(generateFailure(), Reason.THROTTLED);
        assertSentExactlyOnce(GENERATE);
    }

    @Test
    @DisplayName("generate, 500: the coupon may exist, OutcomeUnknown, not retried")
    void generate500Unknown() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(json(500,
                "{\"message\":\"Internal Error. Details are available in Magento log file. Report ID: "
                        + "webapi-6527d9f8a1b2d\"}")));

        Throwable thrown = generateFailure();

        assertThat(thrown).isInstanceOfSatisfying(GetMoreOutcomeUnknownException.class,
                e -> assertThat(e.mintedCodes()).isEmpty());
        assertThat(thrown).hasMessageContaining("webapi-6527d9f8a1b2d");
        assertSentExactlyOnce(GENERATE);
        assertThat(counted("generate", "outcome_unknown")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("generate, a 500 whose template holds a placeholder index too long for an int: still OutcomeUnknown,"
            + " the text kept as it came")
    void generate500OddTemplateStillUnknown() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(json(500,
                "{\"message\":\"x %99999999999\",\"parameters\":[\"a\"]}")));

        Throwable thrown = generateFailure();

        assertThat(thrown).isInstanceOf(GetMoreOutcomeUnknownException.class)
                .hasMessageContaining("x %99999999999");
        assertSentExactlyOnce(GENERATE);
        assertThat(counted("generate", "outcome_unknown")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("generate, Magento's masked database error (400 'Internal Error... Report ID'): OutcomeUnknown")
    void generateMaskedDbError400Unknown() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(json(400,
                "{\"message\":\"Internal Error. Details are available in Magento log file. Report ID: "
                        + "webapi-6527d9f8a1b2c\"}")));

        assertThat(generateFailure()).isInstanceOf(GetMoreOutcomeUnknownException.class)
                .hasMessageContaining("Report ID: webapi-6527d9f8a1b2c");
        assertSentExactlyOnce(GENERATE);
    }

    @Test
    @DisplayName("generate, a 200 that is an HTML page (a proxy in front): OutcomeUnknown")
    void generateHtml200Unknown() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(html(200)));

        assertThat(generateFailure()).isInstanceOf(GetMoreOutcomeUnknownException.class)
                .hasMessageContaining("HTTP 200");
        assertSentExactlyOnce(GENERATE);
    }

    @Test
    @DisplayName("generate, the answer never comes (read timeout): OutcomeUnknown, sent once")
    void generateReadTimeoutUnknown() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(json(200, fixture("generate-200.json"))
                .withFixedDelay(2500)));

        assertThat(generateFailure()).isInstanceOf(GetMoreOutcomeUnknownException.class)
                .hasMessageContaining("may have been sent");
        assertSentExactlyOnce(GENERATE);
    }

    @Test
    @DisplayName("generate, the connection is reset after the send: OutcomeUnknown, sent once")
    void generateResetUnknown() {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(generateFailure()).isInstanceOf(GetMoreOutcomeUnknownException.class);
        assertSentExactlyOnce(GENERATE);
    }

    @Test
    @DisplayName("generate, connection refused: nothing was sent, Unavailable NOT_REACHED")
    void generateConnectRefusedNotReached() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        MagentoGetMoreStore dead = store(properties("http://localhost:" + closedPort + BASE));

        Throwable thrown = catchFailure(() -> dead.generateCoupons(GetMoreCouponSpec.single(43, PREFIX, 16)));

        assertUnavailable(thrown, Reason.NOT_REACHED);
        assertThat(thrown).hasMessageNotContaining("localhost");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
        assertThat(counted("generate", "not_reached")).isEqualTo(1.0);
    }

    // ---- coupons/search ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("search by prefix: code like '<prefix>%' encoded on the wire ('%' as %25), coupon_id ASC, fields")
    void searchByPrefixLikeEncoded() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(json(200, fixture("coupon-search-page1.json"))));

        GetMorePage<GetMoreCoupon> page = store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 2));

        assertThat(page.items()).extracting(GetMoreCoupon::couponId).containsExactly(1207L, 1208L);
        assertThat(page.totalCount()).isEqualTo(3);
        LoggedRequest request = onlyRequest("GET");
        assertThat(request.getUrl()).contains("INN-K7Q2M3XW-%25").doesNotContain("[", "+", " ");
        wireMock.verify(getRequestedFor(urlPathEqualTo(BASE + "/coupons/search"))
                .withQueryParam("searchCriteria[filter_groups][0][filters][0][field]", equalTo("code"))
                .withQueryParam("searchCriteria[filter_groups][0][filters][0][value]", equalTo(PREFIX + "%"))
                .withQueryParam("searchCriteria[filter_groups][0][filters][0][condition_type]", equalTo("like"))
                .withQueryParam("searchCriteria[sortOrders][0][field]", equalTo("coupon_id"))
                .withQueryParam("searchCriteria[sortOrders][0][direction]", equalTo("ASC"))
                .withQueryParam("searchCriteria[pageSize]", equalTo("2"))
                .withQueryParam("searchCriteria[currentPage]", equalTo("1"))
                .withQueryParam("fields", equalTo(MagentoSearchCriteria.COUPON_FIELDS))
                .withoutHeader("traceparent"));
    }

    @Test
    @DisplayName("search, two pages: the first is not last, the second (reaching total_count) is")
    void searchTwoPages() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search"))
                .withQueryParam("searchCriteria[currentPage]", equalTo("1"))
                .willReturn(json(200, fixture("coupon-search-page1.json"))));
        stub(get(urlPathEqualTo(BASE + "/coupons/search"))
                .withQueryParam("searchCriteria[currentPage]", equalTo("2"))
                .willReturn(json(200, fixture("coupon-search-page2.json"))));

        GetMorePage<GetMoreCoupon> first = store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 2));
        GetMorePage<GetMoreCoupon> second = store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 2, 2));

        assertThat(first.last()).isFalse();
        assertThat(second.last()).isTrue();
        assertThat(second.items()).extracting(GetMoreCoupon::couponId).containsExactly(1209L);
        assertThat(second.page()).isEqualTo(2);
    }

    @Test
    @DisplayName("search: a field Magento omits because it is null (expiration_date) reads as null")
    void searchOmittedNullFields() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(json(200, fixture("coupon-search-page1.json"))));

        List<GetMoreCoupon> coupons = store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 2)).items();

        GetMoreCoupon unexpiring = coupons.get(0);
        assertThat(unexpiring.code()).isEqualTo(CODE);
        assertThat(unexpiring.expirationDateUtc()).isNull();
        assertThat(unexpiring.timesUsed()).isZero();
        assertThat(unexpiring.primary()).isFalse();
        assertThat(unexpiring.createdAtUtc()).isEqualTo(LocalDateTime.of(2026, 10, 14, 8, 30, 5));
        assertThat(coupons.get(1).expirationDateUtc()).isEqualTo(LocalDateTime.of(2026, 11, 13, 21, 59, 59));
        assertThat(coupons.get(1).timesUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("search: items that do not carry the prefix are dropped here (Magento compares loosely)")
    void searchRefiltersByPrefix() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(json(200, "{\"items\":["
                + "{\"coupon_id\":1,\"rule_id\":43,\"code\":\"inn-k7q2m3xw-aaaabbbbccccdddd\",\"times_used\":0},"
                + "{\"coupon_id\":2,\"rule_id\":43,\"code\":\"INN-K7Q2M3XWX-AAAABBBBCCCCDDDD\",\"times_used\":0}],"
                + "\"total_count\":4}")));

        GetMorePage<GetMoreCoupon> page = store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 2));

        assertThat(page.items()).extracting(GetMoreCoupon::couponId).containsExactly(1L);
        assertThat(page.totalCount()).as("Magento's own count, unadjusted").isEqualTo(4);
        assertThat(page.last()).as("a page made short by the re-filter still has pages after it").isFalse();
    }

    @Test
    @DisplayName("search, nothing on the prefix: Magento's fields projection renders the empty list as items null;"
            + " an empty last page, not a bad response")
    void searchEmptyItemsNull() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(json(200, fixture("coupon-search-empty.json"))));

        GetMorePage<GetMoreCoupon> page = store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 50));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isZero();
        assertThat(page.last()).isTrue();
        onlyRequest("GET");
        wireMock.verify(getRequestedFor(urlPathEqualTo(BASE + "/coupons/search"))
                .withQueryParam("fields", equalTo(MagentoSearchCriteria.COUPON_FIELDS)));
        assertThat(counted("search_coupons", "ok")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("search, a page past the end with items null: empty, the true total kept")
    void searchPastTheEndItemsNull() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(json(200,
                "{\"items\":null,\"total_count\":3}")));

        GetMorePage<GetMoreCoupon> page = store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 3, 2));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.last()).isTrue();
    }

    @Test
    @DisplayName("search, items null while total_count says this page has some: a read failed, BAD_RESPONSE")
    void itemsNullWithItemsDueIsBadResponse() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(json(200,
                "{\"items\":null,\"total_count\":3}")));

        Throwable thrown = catchFailure(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 50)));

        assertUnavailable(thrown, Reason.BAD_RESPONSE);
        assertThat(thrown).hasMessageContaining("missing items");
        assertThat(counted("search_coupons", "bad_response")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("search, no total_count: a read failed, BAD_RESPONSE")
    void missingTotalCountIsBadResponse() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(json(200, "{\"items\":null}")));

        assertUnavailable(catchFailure(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 50))),
                Reason.BAD_RESPONSE);
    }

    @Test
    @DisplayName("search, a 500 with Magento's envelope: a read failed, Unavailable UPSTREAM_ERROR")
    void search500Unavailable() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(json(500,
                "{\"message\":\"Internal Error. Details are available in Magento log file. Report ID: "
                        + "webapi-6527d9f8a1b2e\"}")));

        assertUnavailable(catchFailure(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 50))),
                Reason.UPSTREAM_ERROR);
    }

    @Test
    @DisplayName("search, an HTML 503 from a proxy: not Magento's answer, Unavailable BLOCKED")
    void searchHtml503Blocked() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(html(503)));

        assertUnavailable(catchFailure(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 50))),
                Reason.BLOCKED);
    }

    @Test
    @DisplayName("search, a WAF's HTML 403: Unavailable BLOCKED")
    void searchWafHtml403Blocked() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(html(403)));

        assertUnavailable(catchFailure(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 50))),
                Reason.BLOCKED);
        assertThat(counted("search_coupons", "blocked")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("search, a 302: never followed (the signed header would be replayed elsewhere), Unavailable BLOCKED")
    void searchRedirect302Blocked() {
        stub(get(urlPathEqualTo(BASE + "/coupons/search")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://localhost:" + wireMock.port() + "/elsewhere")));
        stub(get(urlEqualTo("/elsewhere")).willReturn(json(200, fixture("coupon-search-page1.json"))));

        assertUnavailable(catchFailure(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 50))),
                Reason.BLOCKED);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(BASE + "/coupons/search")));
        wireMock.verify(0, getRequestedFor(urlEqualTo("/elsewhere")));
    }

    // ---- coupons/{id} ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("coupon by id: GET coupons/1207, every field mapped, the expiry read as UTC")
    void coupon200() {
        stub(get(urlEqualTo(BASE + "/coupons/1207")).willReturn(json(200, fixture("coupon-200.json"))));

        GetMoreCoupon coupon = store.coupon(1207);

        assertThat(coupon.couponId()).isEqualTo(1207);
        assertThat(coupon.ruleId()).isEqualTo(43);
        assertThat(coupon.code()).isEqualTo(CODE);
        assertThat(coupon.usageLimit()).isZero();
        assertThat(coupon.usagePerCustomer()).isZero();
        assertThat(coupon.timesUsed()).isEqualTo(1);
        assertThat(coupon.expirationDateUtc()).isEqualTo(LocalDateTime.of(2026, 11, 13, 21, 59, 59));
        assertThat(coupon.type()).isEqualTo(1);
        assertThat(coupon.toString()).doesNotContain(RANDOM_PART).contains("****8B1C");
        assertThat(onlyRequest("GET").getUrl()).isEqualTo(BASE + "/coupons/1207");
    }

    @Test
    @DisplayName("coupon by id, 404 'No such entity.': NotFound")
    void coupon404NotFound() {
        stub(get(urlEqualTo(BASE + "/coupons/99")).willReturn(json(404, fixture("error-404.json"))));

        assertThat(catchFailure(() -> store.coupon(99))).isInstanceOfSatisfying(GetMoreNotFoundException.class, e -> {
            assertThat(e.renderedMessage()).isEqualTo("No such entity.");
            assertThat(e.operation()).isEqualTo(Operation.GET_COUPON);
        });
        assertThat(counted("get_coupon", "not_found")).isEqualTo(1.0);
    }

    // ---- PUT coupons/{id} --------------------------------------------------------------------------------------

    @Test
    @DisplayName("update: PUT {coupon:{coupon_id, rule_id, code, type, expiration_date}} and nothing else")
    void updateBodyShape() {
        stub(put(urlEqualTo(BASE + "/coupons/1207")).willReturn(json(200, fixture("coupon-200.json"))));

        GetMoreCoupon updated = store.updateCoupon(1207, new GetMoreCouponUpdate(43, CODE, 1,
                LocalDateTime.of(2026, 11, 13, 21, 59, 59)));

        assertThat(updated.expirationDateUtc()).isEqualTo(LocalDateTime.of(2026, 11, 13, 21, 59, 59));
        onlyRequest("PUT");
        wireMock.verify(putRequestedFor(urlEqualTo(BASE + "/coupons/1207"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"coupon\":{\"coupon_id\":1207,\"rule_id\":43,\"code\":\"" + CODE
                        + "\",\"type\":1,\"expiration_date\":\"2026-11-13 21:59:59\"}}")));
    }

    @Test
    @DisplayName("update, a rule mismatch: 'Error occurred when saving coupon: ...' rendered, Rejected INVALID")
    void update400RuleMismatch() {
        stub(put(urlEqualTo(BASE + "/coupons/1207")).willReturn(json(400,
                "{\"message\":\"Error occurred when saving coupon: %1\",\"parameters\":"
                        + "[\"Specified rule only allows auto generated coupons\"]}")));

        assertThat(catchFailure(() -> store.updateCoupon(1207, new GetMoreCouponUpdate(43, CODE, 0, null))))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> assertThat(e.renderedMessage())
                        .isEqualTo("Error occurred when saving coupon: Specified rule only allows auto generated "
                                + "coupons"));
        assertSentExactlyOnce(putRequestedFor(urlEqualTo(BASE + "/coupons/1207")));
    }

    @Test
    @DisplayName("update, a 500: the PUT may have applied, OutcomeUnknown, not retried")
    void update500Unknown() {
        stub(put(urlEqualTo(BASE + "/coupons/1207")).willReturn(json(500, "{\"message\":\"Internal Error.\"}")));

        assertThat(catchFailure(() -> store.updateCoupon(1207, new GetMoreCouponUpdate(43, CODE, 1,
                LocalDateTime.of(2026, 11, 13, 21, 59, 59))))).isInstanceOf(GetMoreOutcomeUnknownException.class)
                .satisfies(noCode());
        assertSentExactlyOnce(putRequestedFor(urlEqualTo(BASE + "/coupons/1207")));
    }

    // ---- deleteByCodes -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("deleteByCodes: POST {codes, ignoreInvalidCoupons} strictly; a repeat reports the code missing")
    void deleteMissingItems() {
        stub(post(urlEqualTo(BASE + "/coupons/deleteByCodes")).willReturn(json(200, fixture("delete-missing.json"))));

        GetMoreCouponDeletion deletion = store.deleteCoupons(List.of(CODE), true);

        assertThat(deletion.missingItems()).containsExactly(CODE);
        assertThat(deletion.failedItems()).isEmpty();
        assertThat(deletion.toString()).doesNotContain(RANDOM_PART);
        onlyRequest("POST");
        wireMock.verify(postRequestedFor(urlEqualTo(BASE + "/coupons/deleteByCodes"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"codes\":[\"" + CODE + "\"],\"ignoreInvalidCoupons\":true}")));
    }

    @Test
    @DisplayName("deleteByCodes: a code Magento could not delete comes back in failed_items")
    void deleteFailedItems() {
        stub(post(urlEqualTo(BASE + "/coupons/deleteByCodes")).willReturn(json(200,
                "{\"failed_items\":[\"" + CODE + "\"],\"missing_items\":[]}")));

        GetMoreCouponDeletion deletion = store.deleteCoupons(List.of(CODE), true);

        assertThat(deletion.failedItems()).containsExactly(CODE);
        assertThat(deletion.missingItems()).isEmpty();
    }

    @Test
    @DisplayName("deleteByCodes with ignoreInvalidCoupons=false and a missing code: 400, nothing deleted, Rejected")
    void deleteInvalidAllOrNothing400() {
        stub(post(urlEqualTo(BASE + "/coupons/deleteByCodes")).willReturn(json(400,
                fixture("delete-invalid-400.json"))));

        assertThat(catchFailure(() -> store.deleteCoupons(List.of(CODE, "INN-K7Q2M3XW-NOPE0000NOPE0000"), false)))
                .isInstanceOfSatisfying(GetMoreRejectedException.class,
                        e -> assertThat(e.renderedMessage()).isEqualTo("Some coupons are invalid."));
        wireMock.verify(postRequestedFor(urlEqualTo(BASE + "/coupons/deleteByCodes")).withRequestBody(equalToJson(
                "{\"codes\":[\"" + CODE + "\",\"INN-K7Q2M3XW-NOPE0000NOPE0000\"],\"ignoreInvalidCoupons\":false}")));
        assertSentExactlyOnce(postRequestedFor(urlEqualTo(BASE + "/coupons/deleteByCodes")));
    }

    @Test
    @DisplayName("deleteByCodes, a 500: the delete may have applied, OutcomeUnknown, not retried")
    void delete500Unknown() {
        stub(post(urlEqualTo(BASE + "/coupons/deleteByCodes")).willReturn(json(503,
                "{\"message\":\"Internal Error.\"}")));

        assertThat(catchFailure(() -> store.deleteCoupons(List.of(CODE), true)))
                .isInstanceOf(GetMoreOutcomeUnknownException.class).satisfies(noCode());
        assertSentExactlyOnce(postRequestedFor(urlEqualTo(BASE + "/coupons/deleteByCodes")));
    }

    // ---- salesRules/{id} ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("sales rule: the full shape (nested conditions ignored); discount 50, 50.0 and 50.0000 are equal")
    void salesRuleFullShape() {
        String rule = fixture("sales-rule-43.json");
        for (String amount : List.of("50", "50.0", "50.0000")) {
            wireMock.resetAll();
            stub(get(urlEqualTo(BASE + "/salesRules/43")).willReturn(json(200,
                    rule.replace("\"discount_amount\": 50,", "\"discount_amount\": " + amount + ","))));

            GetMoreSalesRule read = store.salesRule(43);

            assertThat(read.discountAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
            assertThat(read.ruleId()).isEqualTo(43);
            assertThat(read.name()).isEqualTo("InnBucks Staff Grocery");
            assertThat(read.active()).isTrue();
            assertThat(read.fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(read.toDate()).isEqualTo(LocalDate.of(2027, 9, 30));
            assertThat(read.simpleAction()).isEqualTo("cart_fixed");
            assertThat(read.couponType()).isEqualTo("SPECIFIC_COUPON");
            assertThat(read.useAutoGeneration()).isTrue();
            assertThat(read.usesPerCoupon()).isEqualTo(1);
            assertThat(read.usesPerCustomer()).isZero();
            assertThat(read.stopRulesProcessing()).isTrue();
            assertThat(read.applyToShipping()).isFalse();
            onlyRequest("GET");
        }
    }

    @Test
    @DisplayName("sales rule, 404: NotFound")
    void salesRule404() {
        stub(get(urlEqualTo(BASE + "/salesRules/44")).willReturn(json(404, fixture("error-404.json"))));

        assertThat(catchFailure(() -> store.salesRule(44))).isInstanceOf(GetMoreNotFoundException.class);
    }

    // ---- orders ------------------------------------------------------------------------------------------------

    private void stubOrders() {
        stub(get(urlPathEqualTo(BASE + "/orders")).withQueryParam("searchCriteria[currentPage]", equalTo("1"))
                .willReturn(json(200, fixture("orders-page1.json"))));
        stub(get(urlPathEqualTo(BASE + "/orders")).withQueryParam("searchCriteria[currentPage]", equalTo("2"))
                .willReturn(json(200, fixture("orders-page2.json"))));
    }

    @Test
    @DisplayName("orders: signs kept and amounts exact as BigDecimal (-45.5000 is -45.5000, never through a double)")
    void ordersNegativeSignsExact() {
        stubOrders();

        GetMoreOrder first = store.orders(GetMoreOrderQuery.byPrefix(PREFIX, 1, 2)).items().get(0);

        assertThat(first.discountAmount()).isEqualTo(new BigDecimal("-45.5000"));
        assertThat(first.discountInvoiced()).isEqualTo(new BigDecimal("-45.5000"));
        assertThat(first.discountRefunded()).isEqualTo(BigDecimal.ZERO);
        assertThat(first.subtotal()).isEqualTo(new BigDecimal("52.25"));
        assertThat(first.held()).isEqualByComparingTo("0");
        assertThat(first.couponCode()).isEqualTo(CODE);
        assertThat(first.toString()).doesNotContain(RANDOM_PART);
        LoggedRequest request = onlyRequest("GET");
        assertThat(request.getUrl()).startsWith(BASE + "/orders?");
        wireMock.verify(getRequestedFor(urlPathEqualTo(BASE + "/orders"))
                .withQueryParam("searchCriteria[filter_groups][0][filters][0][field]", equalTo("coupon_code"))
                .withQueryParam("searchCriteria[filter_groups][0][filters][0][value]", equalTo(PREFIX + "%"))
                .withQueryParam("searchCriteria[filter_groups][0][filters][0][condition_type]", equalTo("like"))
                .withQueryParam("searchCriteria[sortOrders][0][field]", equalTo("entity_id"))
                .withQueryParam("searchCriteria[sortOrders][0][direction]", equalTo("ASC"))
                .withQueryParam("searchCriteria[pageSize]", equalTo("2"))
                .withQueryParam("fields", equalTo(MagentoSearchCriteria.ORDER_FIELDS)));
    }

    @Test
    @DisplayName("orders: discount_invoiced/refunded/canceled absent until they exist; each reads as zero")
    void ordersAbsentDiscountFieldsAreZero() {
        stub(get(urlPathEqualTo(BASE + "/orders")).willReturn(json(200, fixture("orders-absent-discounts.json"))));

        GetMoreOrder order = store.orders(GetMoreOrderQuery.byPrefix(PREFIX, 1, 50)).items().get(0);

        assertThat(order.discountInvoiced()).isEqualTo(BigDecimal.ZERO);
        assertThat(order.discountRefunded()).isEqualTo(BigDecimal.ZERO);
        assertThat(order.discountCanceled()).isEqualTo(BigDecimal.ZERO);
        assertThat(order.held()).as("placed, not invoiced: all of it held").isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("orders: store_name keeps Magento's newline-joined website, store and view")
    void ordersStoreNameNewlines() {
        stubOrders();

        assertThat(store.orders(GetMoreOrderQuery.byPrefix(PREFIX, 1, 2)).items().get(0).storeName())
                .isEqualTo("GetMore\nBorrowdale\nDefault Store View");
    }

    @Test
    @DisplayName("orders: applied_rule_ids '43' and '43,12' are split into ids")
    void ordersAppliedRuleIdsSplit() {
        stubOrders();

        List<GetMoreOrder> orders = store.orders(GetMoreOrderQuery.byPrefix(PREFIX, 1, 2)).items();

        assertThat(orders.get(0).appliedRuleIds()).isEqualTo(Set.of(43L));
        assertThat(orders.get(1).appliedRuleIds()).isEqualTo(Set.of(43L, 12L));
    }

    @Test
    @DisplayName("orders: created_at and updated_at are UTC; a created_at window is sent as gteq/lt groups")
    void ordersUtcParse() {
        stubOrders();

        GetMoreOrder order = store.orders(new GetMoreOrderQuery(null, PREFIX, LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 19, 22, 0), 1, 2)).items().get(1);

        assertThat(order.createdAtUtc()).isEqualTo(LocalDateTime.of(2026, 10, 15, 16, 5, 44));
        assertThat(order.updatedAtUtc()).isEqualTo(LocalDateTime.of(2026, 10, 16, 9, 12, 0));
        wireMock.verify(getRequestedFor(urlPathEqualTo(BASE + "/orders"))
                .withQueryParam("searchCriteria[filter_groups][1][filters][0][field]", equalTo("created_at"))
                .withQueryParam("searchCriteria[filter_groups][1][filters][0][value]", equalTo("2026-10-01 00:00:00"))
                .withQueryParam("searchCriteria[filter_groups][1][filters][0][condition_type]", equalTo("gteq"))
                .withQueryParam("searchCriteria[filter_groups][2][filters][0][value]", equalTo("2026-10-19 22:00:00"))
                .withQueryParam("searchCriteria[filter_groups][2][filters][0][condition_type]", equalTo("lt")));
        onlyRequest("GET");
    }

    @Test
    @DisplayName("orders: total_count drives paging; a processing order is held (ordered, not invoiced)")
    void ordersPagingTotalCount() {
        stubOrders();

        GetMorePage<GetMoreOrder> first = store.orders(GetMoreOrderQuery.byPrefix(PREFIX, 1, 2));
        GetMorePage<GetMoreOrder> second = store.orders(GetMoreOrderQuery.byPrefix(PREFIX, 2, 2));

        assertThat(first.totalCount()).isEqualTo(3);
        assertThat(first.last()).isFalse();
        assertThat(second.last()).isTrue();
        GetMoreOrder processing = second.items().get(0);
        assertThat(processing.state()).isEqualTo("processing");
        assertThat(processing.held()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("orders, none placed with the prefix yet (every voucher before its first sale): items null is an"
            + " empty last page")
    void ordersEmptyItemsNull() {
        stub(get(urlPathEqualTo(BASE + "/orders")).willReturn(json(200, fixture("coupon-search-empty.json"))));

        GetMorePage<GetMoreOrder> page = store.orders(GetMoreOrderQuery.byPrefix(PREFIX, 1, 50));

        assertThat(page.items()).isEmpty();
        assertThat(page.last()).isTrue();
        onlyRequest("GET");
        wireMock.verify(getRequestedFor(urlPathEqualTo(BASE + "/orders"))
                .withQueryParam("fields", equalTo(MagentoSearchCriteria.ORDER_FIELDS)));
    }

    // ---- creditmemos -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("credit memos, none for the orders asked: items null is an empty last page")
    void creditMemosEmptyItemsNull() {
        stub(get(urlPathEqualTo(BASE + "/creditmemos")).willReturn(json(200, fixture("coupon-search-empty.json"))));

        GetMorePage<GetMoreCreditMemo> page = store.creditMemos(new GetMoreCreditMemoQuery(List.of(5012L), 1, 50));

        assertThat(page.items()).isEmpty();
        assertThat(page.last()).isTrue();
        onlyRequest("GET");
        wireMock.verify(getRequestedFor(urlPathEqualTo(BASE + "/creditmemos"))
                .withQueryParam("fields", equalTo(MagentoSearchCriteria.CREDIT_MEMO_FIELDS)));
    }

    @Test
    @DisplayName("credit memos: order_id in a,b sent comma-joined, entity_id ASC, fields; every memo mapped")
    void creditMemosInList() {
        stub(get(urlPathEqualTo(BASE + "/creditmemos")).willReturn(json(200, fixture("creditmemos.json"))));

        GetMorePage<GetMoreCreditMemo> page = store.creditMemos(new GetMoreCreditMemoQuery(List.of(5012L, 5013L),
                1, 50));

        assertThat(page.items()).extracting(GetMoreCreditMemo::entityId).containsExactly(301L, 302L, 303L);
        GetMoreCreditMemo refund = page.items().get(0);
        assertThat(refund.orderId()).isEqualTo(5013);
        assertThat(refund.discountAmount()).isEqualTo(new BigDecimal("-10.0000"));
        assertThat(refund.createdAtUtc()).isEqualTo(LocalDateTime.of(2026, 10, 16, 9, 11, 58));
        onlyRequest("GET");
        wireMock.verify(getRequestedFor(urlPathEqualTo(BASE + "/creditmemos"))
                .withQueryParam("searchCriteria[filter_groups][0][filters][0][field]", equalTo("order_id"))
                .withQueryParam("searchCriteria[filter_groups][0][filters][0][value]", equalTo("5012,5013"))
                .withQueryParam("searchCriteria[filter_groups][0][filters][0][condition_type]", equalTo("in"))
                .withQueryParam("searchCriteria[sortOrders][0][field]", equalTo("entity_id"))
                .withQueryParam("fields", equalTo(MagentoSearchCriteria.CREDIT_MEMO_FIELDS)));
    }

    @Test
    @DisplayName("credit memos: only state 2 is refunded; 1 (open) and 3 (cancelled) are not")
    void creditMemoStates() {
        stub(get(urlPathEqualTo(BASE + "/creditmemos")).willReturn(json(200, fixture("creditmemos.json"))));

        List<GetMoreCreditMemo> memos = store.creditMemos(new GetMoreCreditMemoQuery(List.of(5012L, 5013L), 1, 50))
                .items();

        assertThat(memos).extracting(GetMoreCreditMemo::state).containsExactly(2, 3, 1);
        assertThat(memos).extracting(GetMoreCreditMemo::refunded).containsExactly(true, false, false);
    }

    @Test
    @DisplayName("credit memos: an absent discount_amount reads as zero")
    void creditMemoAbsentDiscountIsZero() {
        stub(get(urlPathEqualTo(BASE + "/creditmemos")).willReturn(json(200,
                fixture("creditmemo-absent-discount.json"))));

        GetMoreCreditMemo memo = store.creditMemos(new GetMoreCreditMemoQuery(List.of(5020L), 1, 50)).items().get(0);

        assertThat(memo.discountAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(memo.grandTotal()).isEqualTo(BigDecimal.ZERO);
        assertThat(memo.subtotal()).isEqualTo(new BigDecimal("3.0000"));
    }

    // ---- guard rails: refused before the network ---------------------------------------------------------------

    @Test
    @DisplayName("a blank base URL or OAuth key: Unavailable UNCONFIGURED naming what to set, no request")
    void unconfiguredNoNetwork() {
        GetMoreProperties blank = properties("http://localhost:" + wireMock.port() + BASE);
        blank.getLive().setConsumerKey(" ");
        blank.getLive().setAccessTokenSecret(null);

        Throwable thrown = catchFailure(() -> store(blank).salesRule(43));

        assertUnavailable(thrown, Reason.UNCONFIGURED);
        assertThat(thrown).hasMessageContaining("GETMORE_CONSUMER_KEY").hasMessageContaining(
                "GETMORE_ACCESS_TOKEN_SECRET").hasMessageNotContaining("GETMORE_BASE_URL");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("a page size of 301: refused, no request")
    void pageSize301NoNetwork() {
        assertThatThrownBy(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 301)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.orders(GetMoreOrderQuery.byPrefix(PREFIX, 1, 301)))
                .isInstanceOf(IllegalArgumentException.class);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("a prefix holding '_' (Magento's wildcard in like): refused, no request")
    void underscoreInLikeNoNetwork() {
        assertThatThrownBy(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix("INN_K7Q2M3XW-", 1, 50)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.orders(GetMoreOrderQuery.byPrefix("INN-K7Q2_3XW-", 1, 50)))
                .isInstanceOf(IllegalArgumentException.class);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("eleven filter groups (signatures would disagree past index 9): refused, no request")
    void elevenFilterGroupsNoNetwork() {
        MagentoSearchCriteria criteria = MagentoSearchCriteria.page(1, 50);
        for (int i = 0; i < 11; i++) {
            criteria.eq("code", "x" + i);
        }

        assertThatThrownBy(() -> store.searchCoupons(criteria, c -> true, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("filter groups");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("a call with a transaction open: IllegalStateException, no request")
    void transactionOpenIllegalState() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> store.salesRule(43)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("GetMore is never called with a transaction open");
            assertThatThrownBy(() -> store.generateCoupons(GetMoreCouponSpec.single(43, PREFIX, 16)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("an empty or blank code list for deleteByCodes: refused, no request")
    void deleteNothingNoNetwork() {
        assertThatThrownBy(() -> store.deleteCoupons(List.of(), true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deleteCoupons(List.of(" "), true)).isInstanceOf(IllegalArgumentException.class);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("no full code in any log line or exception, a prefix-less one echoed by Magento included")
    void noFullCodeInLogs(CapturedOutput output) {
        stub(post(urlEqualTo(BASE + "/coupons/generate")).willReturn(json(200, fixture("generate-200.json"))));
        stub(put(urlEqualTo(BASE + "/coupons/1207")).willReturn(json(400,
                "{\"message\":\"Error occurred when saving coupon: %1\",\"parameters\":[\"Coupon code "
                        + RANDOM_PART + " already exists.\"]}")));
        stub(post(urlEqualTo(BASE + "/coupons/deleteByCodes")).willReturn(json(400,
                "{\"message\":\"Coupon %code is invalid.\",\"parameters\":{\"code\":\"" + CODE.toLowerCase()
                        + "\"}}")));
        stub(get(urlEqualTo(BASE + "/coupons/1207")).willReturn(json(200, fixture("coupon-200.json"))));

        store.generateCoupons(GetMoreCouponSpec.single(43, PREFIX, 16));
        store.coupon(1207);
        Throwable put = catchFailure(() -> store.updateCoupon(1207, new GetMoreCouponUpdate(43, CODE, 1, null)));
        Throwable delete = catchFailure(() -> store.deleteCoupons(List.of(CODE), false));

        assertThat(put).satisfies(noCode()).hasMessageContaining("Coupon code ****8B1C already exists.");
        assertThat(delete).satisfies(noCode()).hasMessageContaining("Coupon ****8B1C is invalid.");
        assertThat(output.getOut()).doesNotContain(CODE, RANDOM_PART, RANDOM_PART.toLowerCase())
                .contains("****8B1C", "GetMore GENERATE POST /coupons/generate -> 200");
        assertThat(output.getOut()).doesNotContain("searchCriteria", "oauth_signature",
                MagentoOAuth1SignerTest.CONSUMER_SECRET);
    }

    @Test
    @DisplayName("the exceptions say what each failure means: a rejection names its reason; an unknown names its codes' count")
    void exceptionMessages() {
        GetMoreException unknown = new GetMoreOutcomeUnknownException(Operation.GENERATE, List.of(CODE), null);
        assertThat(unknown.getMessage()).isEqualTo("GetMore GENERATE: the outcome is unknown (1 minted code(s) not "
                + "found by search)");
        assertThat(unknown.toString()).doesNotContain(RANDOM_PART);
        assertThat(((GetMoreOutcomeUnknownException) unknown).mintedCodes()).containsExactly(CODE);
    }
}
