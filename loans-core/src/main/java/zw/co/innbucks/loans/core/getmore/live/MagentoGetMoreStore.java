package zw.co.innbucks.loans.core.getmore.live;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
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
import zw.co.innbucks.loans.core.getmore.GetMoreStore;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException.Reason;
import zw.co.innbucks.loans.core.getmore.live.MagentoFailures.Raw;
import zw.co.innbucks.loans.core.getmore.live.dto.MagentoCouponDeletionDto;
import zw.co.innbucks.loans.core.getmore.live.dto.MagentoCouponDto;
import zw.co.innbucks.loans.core.getmore.live.dto.MagentoCouponSpecDto;
import zw.co.innbucks.loans.core.getmore.live.dto.MagentoCreditMemoDto;
import zw.co.innbucks.loans.core.getmore.live.dto.MagentoOrderDto;
import zw.co.innbucks.loans.core.getmore.live.dto.MagentoSalesRuleDto;
import zw.co.innbucks.loans.core.getmore.live.dto.MagentoSearchResultDto;
import zw.co.innbucks.loans.core.getmore.live.dto.MagentoShapeException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The LIVE {@link GetMoreStore}: GetMore's Magento Open Source 2.4 over REST, signed with OAuth 1.0a
 * ({@link MagentoOAuth1Signer}). Plain class, built only by the configuration's mode switch.
 *
 * <p>Each call: no transaction open; configuration complete (else {@code UNCONFIGURED} before any network call); one
 * absolute {@link URI}, signed exactly as sent; {@code Accept: application/json} (and {@code Content-Type} on POST and
 * PUT); the answer read up to 8 MiB; then classified by {@link MagentoFailures}. Nothing is ever retried, and redirects
 * are never followed (the client is built with them off).
 *
 * <p><b>Logging.</b> One INFO line per call, {@code GetMore {op} {METHOD} {path} -> {status} in {ms}ms}, the path
 * without its query and any coupon shown as {@code ****8B1C}; a failure adds one WARN (ERROR for a refused credential)
 * with the masked classification. Never logged: the query, a body, the {@code Authorization} header, a credential, or a
 * transport exception's message (it carries the URI). Metric {@code loans.getmore.calls{operation,outcome}}.
 *
 * <p>Searches send Magento's {@code fields} projection, so shoppers' names, addresses, phones and payments never reach
 * loans, and prefix searches are re-filtered here: Magento compares case-insensitively and reads {@code _} as a
 * wildcard. {@code total_count} is Magento's, unadjusted.
 */
@Slf4j
public final class MagentoGetMoreStore implements GetMoreStore {

    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    static final String METRIC = "loans.getmore.calls";

    private final RestClient restClient;
    private final GetMoreProperties.Live live;
    private final MagentoOAuth1Signer signer;
    private final MeterRegistry meterRegistry;

    public MagentoGetMoreStore(RestClient restClient, GetMoreProperties.Live live, MagentoOAuth1Signer signer) {
        this(restClient, live, signer, null);
    }

    /** @param meterRegistry where {@value #METRIC} is counted; null counts nothing */
    public MagentoGetMoreStore(RestClient restClient, GetMoreProperties.Live live, MagentoOAuth1Signer signer,
                               MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.live = live;
        this.signer = signer;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GetMoreMode mode() {
        return GetMoreMode.LIVE;
    }

    @Override
    public List<String> generateCoupons(GetMoreCouponSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return call(Operation.GENERATE, HttpMethod.POST, "/coupons/generate", null, MagentoCouponSpecDto.body(spec),
                List.of(), body -> {
                    JsonNode codes = MagentoJson.tree(body);
                    if (codes == null || !codes.isArray()) {
                        throw new MagentoShapeException("not a list of codes");
                    }
                    List<String> out = new ArrayList<>();
                    for (JsonNode code : codes) {
                        if (!code.isString() || code.asString().isBlank()) {
                            throw new MagentoShapeException("a code that is not a string");
                        }
                        out.add(code.asString());
                    }
                    return List.copyOf(out);
                }, MagentoGetMoreStore::describeCodes);
    }

    @Override
    public GetMorePage<GetMoreCoupon> searchCoupons(GetMoreCouponQuery query) {
        Objects.requireNonNull(query, "query");
        MagentoSearchCriteria criteria = MagentoSearchCriteria.page(query.page(), query.pageSize());
        Predicate<GetMoreCoupon> keep;
        List<String> known;
        if (query.prefix() != null) {
            criteria.likePrefix("code", query.prefix());
            keep = c -> GetMoreCodes.hasPrefix(c.code(), query.prefix());
            known = List.of();
        } else {
            criteria.eq("code", query.code());
            keep = c -> c.code().equalsIgnoreCase(query.code());
            known = List.of(query.code());
        }
        criteria.sortAscending("coupon_id").fields(MagentoSearchCriteria.COUPON_FIELDS);
        return searchCoupons(criteria, keep, known);
    }

    /** The search itself, for any criteria: the guard rails are proved through here. */
    GetMorePage<GetMoreCoupon> searchCoupons(MagentoSearchCriteria criteria, Predicate<GetMoreCoupon> keep,
                                             Collection<String> knownCodes) {
        return page(Operation.SEARCH_COUPONS, "/coupons/search", criteria, knownCodes, MagentoCouponDto.class,
                MagentoCouponDto::toCoupon, keep);
    }

    @Override
    public GetMoreCoupon coupon(long couponId) {
        return call(Operation.GET_COUPON, HttpMethod.GET, "/coupons/" + couponId, null, null, List.of(),
                body -> MagentoJson.read(body, MagentoCouponDto.class).toCoupon(),
                c -> "coupon " + GetMoreCodes.maskedCode(c.code()));
    }

    @Override
    public GetMoreCoupon updateCoupon(long couponId, GetMoreCouponUpdate update) {
        Objects.requireNonNull(update, "update");
        return call(Operation.UPDATE_COUPON, HttpMethod.PUT, "/coupons/" + couponId, null,
                MagentoCouponDto.updateBody(couponId, update), List.of(update.code()),
                body -> MagentoJson.read(body, MagentoCouponDto.class).toCoupon(),
                c -> "coupon " + GetMoreCodes.maskedCode(c.code()));
    }

    @Override
    public GetMoreCouponDeletion deleteCoupons(List<String> codesAsStored, boolean ignoreInvalidCoupons) {
        if (codesAsStored == null || codesAsStored.isEmpty()
                || codesAsStored.stream().anyMatch(c -> c == null || c.isBlank())) {
            throw new IllegalArgumentException("deleteByCodes needs at least one code, none blank");
        }
        return call(Operation.DELETE_COUPONS, HttpMethod.POST, "/coupons/deleteByCodes", null,
                MagentoCouponDeletionDto.body(codesAsStored, ignoreInvalidCoupons), codesAsStored,
                body -> MagentoJson.read(body, MagentoCouponDeletionDto.class).toDeletion(),
                d -> describeCodes(codesAsStored) + ", failed " + d.failedItems().size() + ", missing "
                        + d.missingItems().size());
    }

    @Override
    public GetMoreSalesRule salesRule(long ruleId) {
        return call(Operation.SALES_RULE, HttpMethod.GET, "/salesRules/" + ruleId, null, null, List.of(),
                body -> MagentoJson.read(body, MagentoSalesRuleDto.class).toRule(), r -> null);
    }

    @Override
    public GetMorePage<GetMoreOrder> orders(GetMoreOrderQuery query) {
        Objects.requireNonNull(query, "query");
        MagentoSearchCriteria criteria = MagentoSearchCriteria.page(query.page(), query.pageSize());
        Predicate<GetMoreOrder> keep;
        List<String> known;
        if (query.couponPrefix() != null) {
            criteria.likePrefix("coupon_code", query.couponPrefix());
            keep = o -> GetMoreCodes.hasPrefix(o.couponCode(), query.couponPrefix());
            known = List.of();
        } else {
            criteria.eq("coupon_code", query.couponCode());
            keep = o -> query.couponCode().equalsIgnoreCase(o.couponCode());
            known = List.of(query.couponCode());
        }
        if (query.createdFromUtc() != null) {
            criteria.gteq("created_at", query.createdFromUtc());
        }
        if (query.createdToUtc() != null) {
            criteria.lt("created_at", query.createdToUtc());
        }
        criteria.sortAscending("entity_id").fields(MagentoSearchCriteria.ORDER_FIELDS);
        return page(Operation.ORDERS, "/orders", criteria, known, MagentoOrderDto.class, MagentoOrderDto::toOrder,
                keep);
    }

    @Override
    public GetMorePage<GetMoreCreditMemo> creditMemos(GetMoreCreditMemoQuery query) {
        Objects.requireNonNull(query, "query");
        Set<Long> asked = new HashSet<>(query.orderIds());
        MagentoSearchCriteria criteria = MagentoSearchCriteria.page(query.page(), query.pageSize())
                .in("order_id", query.orderIds())
                .sortAscending("entity_id")
                .fields(MagentoSearchCriteria.CREDIT_MEMO_FIELDS);
        return page(Operation.CREDIT_MEMOS, "/creditmemos", criteria, List.of(), MagentoCreditMemoDto.class,
                MagentoCreditMemoDto::toCreditMemo, m -> asked.contains(m.orderId()));
    }

    // ---- the one request path ------------------------------------------------------------------------------------

    private <D, T> GetMorePage<T> page(Operation op, String path, MagentoSearchCriteria criteria,
                                       Collection<String> knownCodes, Class<D> itemType, Function<D, T> toItem,
                                       Predicate<T> keep) {
        return call(op, HttpMethod.GET, path, criteria, null, knownCodes, body -> {
            MagentoSearchResultDto<D> result = MagentoJson.read(body, MagentoJson.searchResultOf(itemType));
            List<T> items = new ArrayList<>();
            for (D item : result.requiredItems()) {
                if (item == null) {
                    throw new MagentoShapeException("a null item");
                }
                T mapped = toItem.apply(item);
                if (keep.test(mapped)) {
                    items.add(mapped);
                }
            }
            return new GetMorePage<>(items, result.requiredTotalCount(), criteria.currentPage(),
                    criteria.pageSize());
        }, p -> p.items().size() + " of " + p.totalCount());
    }

    private <T> T call(Operation op, HttpMethod method, String path, MagentoSearchCriteria criteria, Object body,
                       Collection<String> knownCodes, Function<byte[], T> mapper, Function<T, String> describe) {
        GetMoreCalls.requireNoTransaction();
        requireConfigured(op);
        String query = criteria == null ? "" : criteria.toQuery().encoded();   // refused here, before the network
        URI uri = URI.create(stripTrailingSlash(live.getBaseUrl()) + path + (query.isEmpty() ? "" : "?" + query));
        String authorization = signer.authorization(method.name(), uri);
        byte[] json = body == null ? null : MagentoJson.write(body);

        long started = System.nanoTime();
        Raw raw;
        try {
            raw = exchange(method, uri, authorization, json);
        } catch (RuntimeException sendFailed) {
            GetMoreException failure = MagentoFailures.classify(op, sendFailed);
            log.info("GetMore {} {} {} -> {} in {}ms", op, method.name(), path,
                    MagentoFailures.innermostType(sendFailed), elapsedMs(started));
            throw fail(op, failure);
        }
        if (!raw.successful()) {
            log.info("GetMore {} {} {} -> {} in {}ms", op, method.name(), path, raw.status(), elapsedMs(started));
            throw fail(op, MagentoFailures.classify(op, raw, knownCodes));
        }
        T result;
        try {
            if (raw.truncated()) {
                throw new MagentoShapeException("a body over " + MAX_BODY_BYTES + " bytes");
            }
            result = mapper.apply(raw.body());
        } catch (RuntimeException unreadable) {
            log.info("GetMore {} {} {} -> {} in {}ms", op, method.name(), path, raw.status(), elapsedMs(started));
            String detail = unreadable instanceof MagentoShapeException shape
                    ? "the body has " + shape.getMessage()
                    : "the body is not the JSON asked for (" + unreadable.getClass().getSimpleName() + ")";
            throw fail(op, MagentoFailures.badResponse(op, raw.status(), detail));
        }
        String described = describe.apply(result);
        log.info("GetMore {} {} {} -> {} in {}ms{}", op, method.name(), path, raw.status(), elapsedMs(started),
                described == null ? "" : " (" + described + ")");
        count(op, "ok");
        return result;
    }

    private Raw exchange(HttpMethod method, URI uri, String authorization, byte[] json) {
        RestClient.RequestBodySpec request = restClient.method(method).uri(uri)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .accept(MediaType.APPLICATION_JSON);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        return request.exchangeForRequiredValue((req, res) -> {
            MediaType contentType = res.getHeaders().getContentType();
            try (InputStream in = res.getBody()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    int keep = Math.min(read, MAX_BODY_BYTES - total);
                    out.write(buffer, 0, keep);
                    total += keep;
                    if (keep < read) {
                        return new Raw(res.getStatusCode().value(), String.valueOf(contentType), out.toByteArray(),
                                true);
                    }
                }
                return new Raw(res.getStatusCode().value(), String.valueOf(contentType), out.toByteArray(), false);
            }
        });
    }

    private GetMoreException fail(Operation op, GetMoreException failure) {
        if (failure instanceof GetMoreUnavailableException unavailable && unavailable.reason() == Reason.AUTH) {
            log.error("GetMore refused loans' credentials on {} ({}). Check GETMORE_CONSUMER_KEY, "
                            + "GETMORE_CONSUMER_SECRET, GETMORE_ACCESS_TOKEN and GETMORE_ACCESS_TOKEN_SECRET, the "
                            + "integration's API resources in GetMore's admin, and GETMORE_SIGNATURE_BASE_URL if "
                            + "GetMore terminates TLS in front of Magento", op, failure.getMessage());
        } else {
            log.warn("{}", failure.getMessage());
        }
        count(op, outcome(failure));
        return failure;
    }

    private void requireConfigured(Operation op) {
        StringJoiner missing = new StringJoiner(", ");
        if (isBlank(live.getBaseUrl())) missing.add("GETMORE_BASE_URL");
        if (isBlank(live.getConsumerKey())) missing.add("GETMORE_CONSUMER_KEY");
        if (isBlank(live.getConsumerSecret())) missing.add("GETMORE_CONSUMER_SECRET");
        if (isBlank(live.getAccessToken())) missing.add("GETMORE_ACCESS_TOKEN");
        if (isBlank(live.getAccessTokenSecret())) missing.add("GETMORE_ACCESS_TOKEN_SECRET");
        if (missing.length() > 0) {
            GetMoreUnavailableException failure = new GetMoreUnavailableException(op, Reason.UNCONFIGURED,
                    "set " + missing);
            log.warn("{}", failure.getMessage());
            count(op, outcome(failure));
            throw failure;
        }
    }

    private static String outcome(GetMoreException failure) {
        return switch (failure) {
            case GetMoreNotFoundException ignored -> "not_found";
            case GetMoreRejectedException ignored -> "rejected";
            case GetMoreUnavailableException unavailable -> unavailable.reason().name().toLowerCase(Locale.ROOT);
            case GetMoreOutcomeUnknownException ignored -> "outcome_unknown";
        };
    }

    private void count(Operation op, String outcome) {
        if (meterRegistry != null) {
            Counter.builder(METRIC)
                    .description("GetMore store calls, by operation and outcome")
                    .tag("operation", op.name().toLowerCase(Locale.ROOT))
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    private static String describeCodes(List<String> codes) {
        StringJoiner out = new StringJoiner(", ", codes.size() + (codes.size() == 1 ? " code: " : " codes: "), "");
        codes.forEach(c -> out.add(GetMoreCodes.maskedCode(c)));
        return out.toString();
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
