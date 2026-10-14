package zw.co.innbucks.loans.core.getmore.simulator;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponSpec;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponUpdate;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemoQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreNotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.getmore.GetMoreOrderQuery;
import zw.co.innbucks.loans.core.getmore.GetMorePage;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException.Reason;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Magento 2.4's behaviour for the eight calls, over the {@code getmore_sim_*} tables. Every method commits in a
 * transaction of its OWN ({@code REQUIRES_NEW}), so what the simulated store did outlives a rollback of loans' work,
 * exactly as a remote store's would. Reproduced, from Magento's source:
 * <ul>
 *   <li><b>generate</b>: the spec validated (quantity 1..100, length 1..32) or 400 "One or more input exceptions
 *       have occurred."; an unknown rule, or one that does not auto-generate, is a <b>400</b> "Error occurred when
 *       generating coupons: %1" (not a 404); codes are the prefix and random characters, unique without case, given
 *       up after 10 collisions; limits from the rule, {@code type} 1, no expiry, never used; the answer is the codes
 *       only.</li>
 *   <li><b>search</b>: {@code eq} and {@code like} on the code without case, {@code like} reading {@code _} as a
 *       one-character wildcard (Magento does not escape it); a page size over 300 is 400 "Maximum SearchCriteria
 *       pageSize is %max"; a page past the end is empty with the true total; deleted coupons are invisible.</li>
 *   <li><b>GET</b> an unknown or deleted coupon or rule: 404 "No such entity.".</li>
 *   <li><b>PUT</b>: the body merged into the row, the usage limits reset from the rule, {@code expiration_date}
 *       stored (and, assumption A4, enforced at the simulated till); a rule that does not allow the coupon's type is
 *       400 "Error occurred when saving coupon: %1".</li>
 *   <li><b>deleteByCodes</b>: with {@code ignoreInvalidCoupons} false and any code missing, 400 "Some coupons are
 *       invalid." and nothing deleted; otherwise deleted, the rest reported missing; a code sent in another case is
 *       deleted AND reported missing (Magento compares the strings exactly); orders keep their code's text.</li>
 *   <li><b>orders</b> and <b>credit memos</b>: Magento's signs (discounts negative, {@code discount_canceled}
 *       positive), {@code NUMERIC(12,4)}, sorted by {@code entity_id}.</li>
 * </ul>
 * The {@code fields} projection is ignored: the port returns records. Codes never reach a log or a message in full.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "loans.getmore", name = "mode", havingValue = "SIMULATED")
public class SimulatedStoreBackend {

    static final int MAX_PAGE_SIZE = 300;
    static final int MAX_QUANTITY = 100;
    static final int MAX_LENGTH = 32;
    static final int MAX_COLLISIONS = 10;
    static final String INPUT_EXCEPTIONS = "One or more input exceptions have occurred.";
    static final String NO_SUCH_ENTITY = "No such entity.";

    private final SimRuleRepository rules;
    private final SimCouponRepository coupons;
    private final SimOrderRepository orders;
    private final SimCreditMemoRepository creditMemos;
    private final SimCodeGenerator codeGenerator;
    private final MarketTimeZone marketTimeZone;

    /** Takes the seeder only so the simulated rule exists before the first call. */
    public SimulatedStoreBackend(SimRuleRepository rules, SimCouponRepository coupons, SimOrderRepository orders,
                                 SimCreditMemoRepository creditMemos, SimCodeGenerator codeGenerator,
                                 MarketTimeZone marketTimeZone, SimulatedStoreSeeder seeder) {
        this.rules = rules;
        this.coupons = coupons;
        this.orders = orders;
        this.creditMemos = creditMemos;
        this.codeGenerator = codeGenerator;
        this.marketTimeZone = marketTimeZone;
        Objects.requireNonNull(seeder, "seeder");
    }

    /** {@code POST /V1/coupons/generate}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> generate(GetMoreCouponSpec spec) {
        if (spec.quantity() < 1 || spec.quantity() > MAX_QUANTITY || spec.length() < 1
                || spec.length() > MAX_LENGTH) {
            throw new GetMoreRejectedException(Operation.GENERATE, 400, Reason.INVALID, INPUT_EXCEPTIONS);
        }
        SimRule rule = rules.findById(spec.ruleId()).orElseThrow(
                () -> generateError("No such entity with rule_id = " + spec.ruleId()));
        if (!rule.generatesCoupons()) {
            throw generateError("Specified rule does not allow automatic coupon generation");
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        List<String> codes = new ArrayList<>();
        Set<String> drawn = new HashSet<>();
        for (int i = 0; i < spec.quantity(); i++) {
            String code = uniqueCode(spec, drawn);
            coupons.save(SimCoupon.builder()
                    .ruleId(rule.getRuleId())
                    .code(code)
                    .usageLimit(rule.getUsesPerCoupon())
                    .usagePerCustomer(rule.getUsesPerCustomer())
                    .timesUsed(0)
                    .primary(false)
                    .type(1)
                    .createdAt(now)
                    .build());
            codes.add(code);
        }
        log.info("GetMore simulator: generated {} code(s) {} under rule {}", codes.size(),
                codes.stream().map(GetMoreCodes::maskedCode).toList(), rule.getRuleId());
        return List.copyOf(codes);
    }

    /** {@code GET /V1/coupons/search}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public GetMorePage<GetMoreCoupon> searchCoupons(GetMoreCouponQuery query) {
        Pageable page = page(Operation.SEARCH_COUPONS, query.page(), query.pageSize(), "couponId");
        Specification<SimCoupon> matches = (root, criteria, cb) -> {
            Expression<String> code = cb.upper(root.get("code"));
            Predicate match = query.prefix() != null
                    ? cb.like(code, upper(query.prefix()) + "%")
                    : cb.equal(code, upper(query.code()));
            return cb.and(cb.isNull(root.get("deletedAt")), match);
        };
        return pageOf(coupons.findAll(matches, page), query.page(), query.pageSize(), SimShapes::toCoupon);
    }

    /** {@code GET /V1/coupons/{couponId}}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public GetMoreCoupon coupon(long couponId) {
        return coupons.findById(couponId).filter(c -> !c.isDeleted()).map(SimShapes::toCoupon)
                .orElseThrow(() -> new GetMoreNotFoundException(Operation.GET_COUPON, NO_SUCH_ENTITY));
    }

    /** {@code PUT /V1/coupons/{couponId}}: the body merged into the stored coupon. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GetMoreCoupon updateCoupon(long couponId, GetMoreCouponUpdate update) {
        SimCoupon coupon = coupons.lockById(couponId).filter(c -> !c.isDeleted()).orElseThrow(
                () -> new GetMoreRejectedException(Operation.UPDATE_COUPON, 404, Reason.NOT_FOUND, NO_SUCH_ENTITY));
        if (update.type() != 0 && update.type() != 1) {
            throw new GetMoreRejectedException(Operation.UPDATE_COUPON, 400, Reason.INVALID, INPUT_EXCEPTIONS);
        }
        SimRule rule = rules.findById(update.ruleId()).orElseThrow(
                () -> saveError("No such entity with rule_id = " + update.ruleId()));
        if ("NO_COUPON".equals(rule.getCouponType())) {
            throw saveError("Specified rule does not allow coupons");
        }
        if (rule.isUseAutoGeneration() && update.type() == 0) {
            throw saveError("Specified rule only allows auto generated coupons");
        }
        if (!rule.isUseAutoGeneration() && update.type() == 1) {
            throw saveError("Specified rule does not allow auto generated coupons");
        }
        if (update.code() != null && !update.code().equals(coupon.getCode())) {
            if (update.code().isBlank() || coupons.codeTakenByAnother(update.code(), couponId)) {
                throw saveError("The coupon code is already in use or is not valid.");
            }
            coupon.setCode(update.code());
        }
        coupon.setRuleId(rule.getRuleId());
        coupon.setType(update.type());
        coupon.setUsageLimit(rule.getUsesPerCoupon());
        coupon.setUsagePerCustomer(rule.getUsesPerCustomer());
        if (update.expirationDateUtc() != null) {
            coupon.setExpirationDate(update.expirationDateUtc());
        }
        coupons.flush();
        log.info("GetMore simulator: coupon {} ({}) saved, expiration date {}", couponId,
                GetMoreCodes.maskedCode(coupon.getCode()), coupon.getExpirationDate());
        return SimShapes.toCoupon(coupon);
    }

    /** {@code POST /V1/coupons/deleteByCodes}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GetMoreCouponDeletion deleteCoupons(List<String> codes, boolean ignoreInvalidCoupons) {
        if (codes == null || codes.isEmpty() || codes.stream().anyMatch(c -> c == null || c.isBlank())) {
            throw new IllegalArgumentException("deleteByCodes needs at least one code, none blank");
        }
        List<String> upperCodes = codes.stream().map(SimulatedStoreBackend::upper).distinct().toList();
        List<SimCoupon> found = coupons.lockLiveByUpperCodeIn(upperCodes);
        if (!ignoreInvalidCoupons && found.size() != codes.size()) {
            throw new GetMoreRejectedException(Operation.DELETE_COUPONS, 400, Reason.INVALID,
                    "Some coupons are invalid.");
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        Set<String> deletedAsStored = new HashSet<>();
        for (SimCoupon coupon : found) {
            coupon.setDeletedAt(now);
            deletedAsStored.add(coupon.getCode());
        }
        coupons.flush();
        // Magento lists as missing every code sent that is not, as an exact string, one it deleted.
        List<String> missing = codes.stream().filter(code -> !deletedAsStored.contains(code)).toList();
        log.info("GetMore simulator: deleted {} coupon(s) of {} code(s) sent; {} reported missing", found.size(),
                codes.size(), missing.size());
        return new GetMoreCouponDeletion(List.of(), missing);
    }

    /** {@code GET /V1/salesRules/{ruleId}}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public GetMoreSalesRule salesRule(long ruleId) {
        return rules.findById(ruleId).map(SimShapes::toRule)
                .orElseThrow(() -> new GetMoreNotFoundException(Operation.SALES_RULE, NO_SUCH_ENTITY));
    }

    /** {@code GET /V1/orders}: by coupon code or prefix, within an optional {@code created_at} window. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public GetMorePage<GetMoreOrder> orders(GetMoreOrderQuery query) {
        Pageable page = page(Operation.ORDERS, query.page(), query.pageSize(), "entityId");
        Specification<SimOrder> matches = (root, criteria, cb) -> {
            List<Predicate> all = new ArrayList<>();
            Expression<String> code = cb.upper(root.get("couponCode"));
            all.add(query.couponPrefix() != null
                    ? cb.like(code, upper(query.couponPrefix()) + "%")
                    : cb.equal(code, upper(query.couponCode())));
            if (query.createdFromUtc() != null) {
                all.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.createdFromUtc()));
            }
            if (query.createdToUtc() != null) {
                all.add(cb.lessThan(root.get("createdAt"), query.createdToUtc()));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
        return pageOf(orders.findAll(matches, page), query.page(), query.pageSize(), SimShapes::toOrder);
    }

    /** {@code GET /V1/creditmemos}: {@code order_id in}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public GetMorePage<GetMoreCreditMemo> creditMemos(GetMoreCreditMemoQuery query) {
        Pageable page = page(Operation.CREDIT_MEMOS, query.page(), query.pageSize(), "entityId");
        return pageOf(creditMemos.findByOrderIdIn(query.orderIds(), page), query.page(), query.pageSize(),
                SimShapes::toCreditMemo);
    }

    /**
     * Assumption A1, not Magento: binds the coupon's own allowance, once. The same value again is nothing; another is
     * refused, so a coupon is never re-valued.
     *
     * @throws GetMoreNotFoundException  the coupon is gone
     * @throws GetMoreRejectedException  {@code VALUE_UNSUPPORTED}: it is already worth something else
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bindAllowance(long couponId, BigDecimal faceValue, String currency) {
        if (faceValue == null || faceValue.signum() <= 0 || currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("An allowance is a positive amount in a currency");
        }
        SimCoupon coupon = coupons.lockById(couponId).filter(c -> !c.isDeleted())
                .orElseThrow(() -> new GetMoreNotFoundException(Operation.GET_COUPON, NO_SUCH_ENTITY));
        String isoCurrency = currency.strip().toUpperCase(Locale.ROOT);
        if (coupon.getAllowance() == null) {
            coupon.setAllowance(faceValue.setScale(4, RoundingMode.UNNECESSARY));
            coupon.setAllowanceCurrency(isoCurrency);
            coupons.flush();
            log.info("GetMore simulator: coupon {} ({}) is worth {} {}", couponId,
                    GetMoreCodes.maskedCode(coupon.getCode()), isoCurrency, faceValue);
            return;
        }
        if (coupon.getAllowance().compareTo(faceValue) == 0 && isoCurrency.equals(coupon.getAllowanceCurrency())) {
            return;
        }
        throw new GetMoreRejectedException(null, Reason.VALUE_UNSUPPORTED, "simulated coupon " + couponId + " ("
                + GetMoreCodes.maskedCode(coupon.getCode()) + ") is already worth " + coupon.getAllowanceCurrency()
                + " " + coupon.getAllowance().stripTrailingZeros().toPlainString() + ", not " + isoCurrency + " "
                + faceValue.toPlainString());
    }

    private String uniqueCode(GetMoreCouponSpec spec, Set<String> drawn) {
        for (int attempt = 0; attempt < MAX_COLLISIONS; attempt++) {
            String code = codeGenerator.code(spec.prefix(), spec.length(), spec.format());
            if (drawn.add(upper(code)) && !coupons.codeTaken(code)) {
                return code;
            }
        }
        throw generateError("We cannot create the requested Coupon Qty. Please check your settings and try again.");
    }

    private static Pageable page(Operation operation, int page, int pageSize, String sortedBy) {
        if (pageSize > MAX_PAGE_SIZE) {
            throw new GetMoreRejectedException(operation, 400, Reason.INVALID,
                    "Maximum SearchCriteria pageSize is " + MAX_PAGE_SIZE);
        }
        if (page < 1 || pageSize < 1) {
            throw new GetMoreRejectedException(operation, 400, Reason.INVALID, INPUT_EXCEPTIONS);
        }
        return PageRequest.of(page - 1, pageSize, Sort.by(sortedBy));
    }

    private static <E, T> GetMorePage<T> pageOf(Page<E> found, int page, int pageSize, Function<E, T> toItem) {
        return new GetMorePage<>(found.getContent().stream().map(toItem).toList(),
                Math.toIntExact(found.getTotalElements()), page, pageSize);
    }

    private static GetMoreRejectedException generateError(String reason) {
        return new GetMoreRejectedException(Operation.GENERATE, 400, Reason.INVALID,
                "Error occurred when generating coupons: " + reason);
    }

    private static GetMoreRejectedException saveError(String reason) {
        return new GetMoreRejectedException(Operation.UPDATE_COUPON, 400, Reason.INVALID,
                "Error occurred when saving coupon: " + reason);
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }
}
