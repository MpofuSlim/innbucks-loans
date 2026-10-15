package zw.co.innbucks.loans.core.getmore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToLongFunction;

/**
 * Everything loans decides about GetMore coupons, above the {@link GetMoreStore} port and the same in every
 * {@link GetMoreMode}: so staging's simulator exercises exactly the code that runs live, and going live is a change of
 * configuration. Every method is called with no transaction open (the store refuses otherwise).
 *
 * <ul>
 *   <li>{@link #findOrCreate}: the voucher's coupon under its issuance's own random prefix. Magento's generate has no
 *       idempotency key, so EVERY attempt searches the prefix first and never generates after a failed search; a
 *       generate is counted durably before the POST ({@link GenerateGuard}); a code that does not carry the prefix is
 *       withdrawn at once; a coupon not made by loans (another rule, or created before the first claim) is never
 *       adopted; once a generate has been sent its code is always looked for, and is never left live and unreported
 *       when the attempt parks.</li>
 *   <li>{@link #activity}: the prefix's coupons, the orders placed with them and their credit memos, for a sync.</li>
 *   <li>{@link #setExpiry} and {@link #withdraw}: a PUT of {@code expiration_date} (which Magento stores but does not
 *       enforce) and {@code deleteByCodes} (the only real stop).</li>
 * </ul>
 *
 * <p>A {@link Deadline} is checked before every call, never during one, and not at all for the search that follows a
 * generate: the code was sent for, so it is looked for whatever the budget. Codes are logged only as
 * {@code ****8B1C}.
 */
@Slf4j
@Component
public class GetMoreCoupons {

    /** Coupon pages read for one prefix; more means the prefix is not loans' alone. */
    static final int MAX_COUPON_PAGES = 3;
    /** Order pages read for one prefix; more and the activity is incomplete. */
    static final int MAX_ORDER_PAGES = 20;
    /** Credit-memo pages read for one chunk of orders; more and the activity is incomplete. */
    static final int MAX_MEMO_PAGES = 20;

    private final GetMoreStore store;
    private final GetMoreAmountBinding binding;
    private final GetMoreProperties properties;
    private final Clock clock;

    public GetMoreCoupons(GetMoreStore store, GetMoreAmountBinding binding, GetMoreProperties properties,
                          MarketTimeZone marketTimeZone) {
        this.store = store;
        this.binding = binding;
        this.properties = properties;
        this.clock = marketTimeZone.clock();
    }

    /**
     * The coupons on a prefix, every page up to {@value #MAX_COUPON_PAGES}, re-filtered here to codes that really start
     * with it (Magento compares without case and reads {@code _} as a wildcard), by coupon id.
     *
     * @param complete false when there were more pages than that: nothing may be concluded from what is absent
     */
    public record OnPrefix(List<GetMoreCoupon> coupons, boolean complete) {

        public OnPrefix {
            coupons = List.copyOf(coupons);
        }
    }

    /**
     * Finds the voucher's coupon on its prefix, or generates it, and binds it to the voucher's value. In order:
     * <ol>
     *   <li>the sales rule, and the amount binding's precheck (it refuses before anything is minted);</li>
     *   <li>search the prefix; a failure propagates, so a failed search never leads to a generate;</li>
     *   <li>nothing found: count the generate ({@link GenerateGuard#beforeGenerate}), generate one code, give the count
     *       back if the store provably did not process it; check the code carries the prefix and length asked for
     *       (else withdraw it at once and park); search again, with no deadline (the code was sent for, so it is
     *       always looked for), and if it is still not there (whatever else is), or that search fails, the outcome is
     *       unknown with the code in hand ({@link GetMoreOutcomeUnknownException#mintedCodes()});</li>
     *   <li>classify what is on the prefix: a coupon under another rule or created before {@code notBeforeUtc} is
     *       foreign; several, more than one used, is a duplicate; several otherwise keeps the lowest id (or the one
     *       used) and withdraws the rest; a used coupon this call did not generate is not adopted;</li>
     *   <li>bind the kept coupon to the voucher's value.</li>
     * </ol>
     * An attempt that generated a code and then parks withdraws that code first (best effort); any it cannot confirm
     * gone is returned as a live orphan ({@link FindOrCreate.Parked#liveOrphanCodes()}), never dropped.
     *
     * @throws GetMoreUnavailableException    a read before the generate failed, the deadline passed before the
     *                                        generate, or the generate was not processed
     * @throws GetMoreOutcomeUnknownException the generate may have minted a coupon that a search cannot find yet, or
     *                                        could not search for
     * @throws GetMoreRejectedException       the store or the amount binding refused
     * @throws ClaimLostException             this attempt lost its claim before the generate: nothing was sent
     */
    public FindOrCreate findOrCreate(CouponRequest request, Deadline deadline) {
        Objects.requireNonNull(request, "request");
        Deadline budget = deadline == null ? Deadline.none() : deadline;
        GetMoreCalls.requireNoTransaction();

        budget.check(clock);
        GetMoreSalesRule rule = store.salesRule(request.ruleId());
        binding.precheck(rule, request.faceValue(), request.currency(), request.voucherLastDay());

        OnPrefix found = onPrefix(request.prefix(), budget);
        if (!found.complete()) {
            return tooMany(request, List.of());
        }
        List<String> generated = List.of();
        if (found.coupons().isEmpty()) {
            budget.check(clock);
            try {
                request.guard().beforeGenerate();
            } catch (GenerateNotAllowedException notAllowed) {
                log.warn("GetMore: nothing on prefix {} and no generate left ({}): parked for an operator",
                        request.prefix(), notAllowed.getMessage());
                return new FindOrCreate.Parked(FindOrCreate.GENERATED_NOT_FOUND, "no coupon on prefix "
                        + request.prefix() + " and its generate allowance is used up: " + notAllowed.getMessage(),
                        List.of());
            }
            List<String> codes;
            try {
                codes = store.generateCoupons(GetMoreCouponSpec.single(request.ruleId(), request.prefix(),
                        request.length()));
            } catch (GetMoreRejectedException | GetMoreUnavailableException notProcessed) {
                giveBack(request.guard(), notProcessed);
                throw notProcessed;
            }
            Optional<FindOrCreate> refused = echoGuard(request, codes);
            if (refused.isPresent()) {
                return refused.get();
            }
            generated = List.copyOf(codes);
            try {
                found = onPrefix(request.prefix(), Deadline.none());   // the generate was sent: finish finding it
            } catch (GetMoreRejectedException | GetMoreUnavailableException searchFailed) {
                throw new GetMoreOutcomeUnknownException(Operation.GENERATE, codes, "the code generated on prefix "
                        + request.prefix() + " could not be searched for (" + reasonOf(searchFailed) + ")");
            }
            if (found.coupons().isEmpty()) {
                throw new GetMoreOutcomeUnknownException(Operation.GENERATE, codes,
                        "the code generated on prefix " + request.prefix() + " is not found by a search yet");
            }
            if (!found.complete()) {
                return tooMany(request, generated);
            }
            if (!containsAny(found.coupons(), generated)) {
                // Others on the prefix (an earlier attempt's, say) but not the code just made: Magento's search lags.
                // Classifying now would adopt over it and leave it live with nobody knowing; reported instead, the
                // next attempt sees both, keeps one and withdraws the other.
                throw new GetMoreOutcomeUnknownException(Operation.GENERATE, codes, "the code generated on prefix "
                        + request.prefix() + " is not found by a search yet (" + found.coupons().size()
                        + " other coupon(s) on it are)");
            }
        }
        return classify(request, found.coupons(), generated);
    }

    /**
     * The prefix's coupons, every order placed with a code on it, and the credit memos of the orders that have a refund
     * (Magento's {@code discount_refunded}) or that loans has recorded one for. Orders are read up to
     * {@value #MAX_ORDER_PAGES} pages, memos in chunks of {@value GetMoreCreditMemoQuery#MAX_ORDER_IDS} orders; past
     * either, {@code complete} is false.
     */
    public CouponActivity activity(String prefix, Set<Long> ordersWithRecordedRefunds, Deadline deadline) {
        Objects.requireNonNull(prefix, "prefix");
        Deadline budget = deadline == null ? Deadline.none() : deadline;
        GetMoreCalls.requireNoTransaction();

        OnPrefix coupons = onPrefix(prefix, budget);
        boolean complete = coupons.complete();

        Map<Long, GetMoreOrder> orders = new LinkedHashMap<>();
        for (int page = 1; ; page++) {
            if (page > MAX_ORDER_PAGES) {
                complete = false;
                log.warn("GetMore: more than {} pages of orders on prefix {}; the activity read is incomplete",
                        MAX_ORDER_PAGES, prefix);
                break;
            }
            budget.check(clock);
            GetMorePage<GetMoreOrder> result = store.orders(GetMoreOrderQuery.byPrefix(prefix, page, pageSize()));
            for (GetMoreOrder order : result.items()) {
                if (GetMoreCodes.hasPrefix(order.couponCode(), prefix)) {
                    orders.putIfAbsent(order.entityId(), order);
                }
            }
            if (result.last()) {
                break;
            }
        }

        TreeSet<Long> refundedOrders = new TreeSet<>();
        if (ordersWithRecordedRefunds != null) {
            refundedOrders.addAll(ordersWithRecordedRefunds);
        }
        orders.values().stream()
                .filter(order -> order.discountRefunded().signum() != 0)
                .map(GetMoreOrder::entityId)
                .forEach(refundedOrders::add);
        Map<Long, GetMoreCreditMemo> memos = new LinkedHashMap<>();
        for (List<Long> chunk : chunks(refundedOrders, GetMoreCreditMemoQuery.MAX_ORDER_IDS)) {
            for (int page = 1; ; page++) {
                if (page > MAX_MEMO_PAGES) {
                    complete = false;
                    log.warn("GetMore: more than {} pages of credit memos for {} orders on prefix {}; the activity"
                            + " read is incomplete", MAX_MEMO_PAGES, chunk.size(), prefix);
                    break;
                }
                budget.check(clock);
                GetMorePage<GetMoreCreditMemo> result =
                        store.creditMemos(new GetMoreCreditMemoQuery(chunk, page, pageSize()));
                result.items().forEach(memo -> memos.putIfAbsent(memo.entityId(), memo));
                if (result.last()) {
                    break;
                }
            }
        }
        return new CouponActivity(coupons.coupons(), sortedBy(orders.values(), GetMoreOrder::entityId),
                sortedBy(memos.values(), GetMoreCreditMemo::entityId), complete);
    }

    /**
     * Tells the store when the coupon lapses ({@code PUT expiration_date}). Magento stores it and does not enforce it
     * at checkout, so this is best effort; a lapsed code is withdrawn by the next sync that touches it.
     */
    public GetMoreCoupon setExpiry(GetMoreCoupon coupon, LocalDateTime expiresAtUtc) {
        Objects.requireNonNull(coupon, "coupon");
        GetMoreCoupon updated = store.updateCoupon(coupon.couponId(), new GetMoreCouponUpdate(coupon.ruleId(),
                coupon.code(), coupon.type() == null ? 1 : coupon.type(), expiresAtUtc));
        log.info("GetMore: coupon {} ({}) expires at {} UTC at the store", coupon.couponId(),
                GetMoreCodes.maskedCode(coupon.code()), expiresAtUtc);
        return updated;
    }

    /**
     * Withdraws codes at the store ({@code deleteByCodes}, invalid ones ignored): the only way to stop a Magento code.
     * Send them exactly as stored; a code already gone comes back as missing. Nothing is sent for no codes.
     */
    public GetMoreCouponDeletion withdraw(List<String> codesAsStored) {
        if (codesAsStored == null || codesAsStored.isEmpty()) {
            return new GetMoreCouponDeletion(List.of(), List.of());
        }
        GetMoreCouponDeletion deletion = store.deleteCoupons(codesAsStored, true);
        log.info("GetMore: withdrew {} code(s) {}: {} failed, {} missing", codesAsStored.size(),
                masked(codesAsStored), deletion.failedItems().size(), deletion.missingItems().size());
        return deletion;
    }

    /** Every coupon page on a prefix, up to {@value #MAX_COUPON_PAGES}. */
    public OnPrefix onPrefix(String prefix, Deadline deadline) {
        Objects.requireNonNull(prefix, "prefix");
        Deadline budget = deadline == null ? Deadline.none() : deadline;
        Map<Long, GetMoreCoupon> coupons = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_COUPON_PAGES; page++) {
            budget.check(clock);
            GetMorePage<GetMoreCoupon> result =
                    store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, page, pageSize()));
            for (GetMoreCoupon coupon : result.items()) {
                if (GetMoreCodes.hasPrefix(coupon.code(), prefix)) {
                    coupons.putIfAbsent(coupon.couponId(), coupon);
                }
            }
            if (result.last()) {
                return new OnPrefix(sortedBy(coupons.values(), GetMoreCoupon::couponId), true);
            }
        }
        return new OnPrefix(sortedBy(coupons.values(), GetMoreCoupon::couponId), false);
    }

    /** The cart price rule, as the store holds it now. */
    public GetMoreSalesRule rule(long ruleId) {
        return store.salesRule(ruleId);
    }

    // ---- findOrCreate's steps ---------------------------------------------------------------------------------

    /**
     * Exactly one code, a well-formed one, on the prefix, of the length asked for. Anything else is withdrawn AT ONCE
     * (best effort) and parked: a code the store made outside the prefix could not be found by a later search, so
     * nothing but this call would ever stop it.
     */
    private Optional<FindOrCreate> echoGuard(CouponRequest request, List<String> codes) {
        List<String> returned = codes == null ? List.of() : codes;
        if (returned.size() == 1) {
            String code = returned.getFirst();
            if (code != null && GetMoreCodes.normalize(code).isPresent()
                    && GetMoreCodes.hasPrefix(code, request.prefix())
                    && code.length() == request.prefix().length() + request.length()) {
                return Optional.empty();
            }
        }
        List<String> sent = returned.stream().filter(c -> c != null && !c.isBlank()).toList();
        Withdrawal withdrawal = tryWithdraw(request.prefix(), sent, "generated outside it");
        List<String> orphans = sent.stream().filter(withdrawal::unconfirmed).toList();
        log.error("GetMore generated {} code(s) {} for prefix {} and length {}: not exactly one code on the prefix."
                        + " Withdrawn at once; {} not confirmed withdrawn. Parked for an operator.", returned.size(),
                masked(sent), request.prefix(), request.length(), orphans.size());
        return Optional.of(new FindOrCreate.Parked(FindOrCreate.PREFIX_NOT_HONOURED, "the store returned "
                + returned.size() + " code(s) " + masked(sent) + " for prefix " + request.prefix() + " and length "
                + request.length() + "; " + orphans.size() + " not confirmed withdrawn", orphans));
    }

    /** @param generated the codes this call generated (none when it generated nothing) */
    private FindOrCreate classify(CouponRequest request, List<GetMoreCoupon> found, List<String> generated) {
        for (GetMoreCoupon coupon : found) {
            String why = foreign(request, coupon);
            if (why != null) {
                log.warn("GetMore: coupon {} ({}) on prefix {} {}: parked, never adopted", coupon.couponId(),
                        GetMoreCodes.maskedCode(coupon.code()), request.prefix(), why);
                return parked(request, FindOrCreate.FOREIGN_COUPON, "coupon " + coupon.couponId() + " ("
                        + GetMoreCodes.maskedCode(coupon.code()) + ") on prefix " + request.prefix() + " " + why,
                        generated, Withdrawal.NONE);
            }
        }
        List<GetMoreCoupon> used = found.stream().filter(c -> c.timesUsed() > 0).toList();
        if (found.size() > 1 && used.size() > 1) {
            log.warn("GetMore: {} coupons on prefix {}, {} of them used: parked", found.size(), request.prefix(),
                    used.size());
            return parked(request, FindOrCreate.DUPLICATE_COUPONS_USED, found.size() + " coupons on prefix "
                    + request.prefix() + ", " + used.size() + " of them used: " + describe(used), generated,
                    Withdrawal.NONE);
        }
        GetMoreCoupon kept = used.size() == 1 ? used.getFirst() : found.getFirst();
        // Made by THIS call, not merely found after it: an older coupon on the prefix that someone already spent is
        // never the voucher, whether or not a generate happened in between.
        boolean madeHere = generated.stream().anyMatch(code -> code.equalsIgnoreCase(kept.code()));
        Withdrawal extras = Withdrawal.NONE;
        if (found.size() > 1) {
            extras = withdrawExtras(request.prefix(), kept,
                    found.stream().filter(c -> c.couponId() != kept.couponId()).toList());
        }
        if (kept.timesUsed() > 0 && !madeHere) {
            log.warn("GetMore: coupon {} ({}) on prefix {} was used {} time(s) before loans bound it: parked",
                    kept.couponId(), GetMoreCodes.maskedCode(kept.code()), request.prefix(), kept.timesUsed());
            return parked(request, FindOrCreate.COUPON_ALREADY_USED, "coupon " + kept.couponId() + " ("
                    + GetMoreCodes.maskedCode(kept.code()) + ") on prefix " + request.prefix() + " was used "
                    + kept.timesUsed() + " time(s) before loans bound it", generated, extras);
        }
        binding.bind(kept, request.faceValue(), request.currency());
        log.info("GetMore: coupon {} ({}) on prefix {} {} and bound to {} {}", kept.couponId(),
                GetMoreCodes.maskedCode(kept.code()), request.prefix(), madeHere ? "generated" : "adopted",
                request.currency(), request.faceValue());
        return new FindOrCreate.Bound(kept, !madeHere, extras.withdrawnMasked());
    }

    /** Why a coupon on the prefix was not made by loans, or null when it could have been. */
    private static String foreign(CouponRequest request, GetMoreCoupon coupon) {
        if (coupon.ruleId() != request.ruleId()) {
            return "is under rule " + coupon.ruleId() + ", not " + request.ruleId();
        }
        LocalDateTime floor = request.notBeforeUtc();
        if (floor != null && (coupon.createdAtUtc() == null || coupon.createdAtUtc().isBefore(floor))) {
            return "was created at " + coupon.createdAtUtc() + " UTC, before loans first claimed the prefix ("
                    + floor + " UTC less the clock skew)";
        }
        return null;
    }

    /**
     * Stops for an operator. When this call generated codes, the coupon is live at the store, worth its face value,
     * and nothing but this call knows it: so each is withdrawn first (best effort, like the echo guard), unless
     * {@code earlier} already sent it, and every one not confirmed gone is returned as a live orphan, never dropped.
     */
    private FindOrCreate.Parked parked(CouponRequest request, String outcome, String detail, List<String> generated,
                                       Withdrawal earlier) {
        if (generated.isEmpty()) {
            return new FindOrCreate.Parked(outcome, detail, List.of());
        }
        List<String> toWithdraw = generated.stream().filter(code -> !earlier.sent().contains(code)).toList();
        Withdrawal now = tryWithdraw(request.prefix(), toWithdraw, "generated by this attempt");
        List<String> orphans = generated.stream()
                .filter(code -> earlier.sent().contains(code) ? earlier.unconfirmed(code) : now.unconfirmed(code))
                .toList();
        if (orphans.isEmpty()) {
            log.warn("GetMore: parked {} on prefix {}; the code(s) {} this attempt generated were withdrawn", outcome,
                    request.prefix(), masked(generated));
        } else {
            log.error("GetMore: parked {} on prefix {}; {} of the code(s) {} this attempt generated not confirmed"
                    + " withdrawn: live at the store, kept for an operator", outcome, request.prefix(), orphans.size(),
                    masked(generated));
        }
        return new FindOrCreate.Parked(outcome, detail + "; " + generated.size() + " code(s) generated by this attempt "
                + masked(generated) + ", " + orphans.size() + " not confirmed withdrawn", orphans);
    }

    /** Best effort: withdraws the extras, logging and swallowing any failure. */
    private Withdrawal withdrawExtras(String prefix, GetMoreCoupon kept, List<GetMoreCoupon> extras) {
        List<String> codes = extras.stream().map(GetMoreCoupon::code).filter(c -> c != null && !c.isBlank()).toList();
        Withdrawal withdrawal = tryWithdraw(prefix, codes, "beside coupon " + kept.couponId());
        if (!codes.isEmpty()) {
            log.warn("GetMore: {} extra coupon(s) on prefix {} beside coupon {} ({}): withdrew {}, {} failed",
                    codes.size(), prefix, kept.couponId(), GetMoreCodes.maskedCode(kept.code()),
                    withdrawal.withdrawnMasked(), withdrawal.failed().size());
        }
        return withdrawal;
    }

    /** One best-effort {@code deleteByCodes} (invalid codes ignored): never throws, nothing sent for no codes. */
    private Withdrawal tryWithdraw(String prefix, List<String> codes, String which) {
        if (codes.isEmpty()) {
            return Withdrawal.NONE;
        }
        try {
            GetMoreCouponDeletion deletion = store.deleteCoupons(codes, true);
            return new Withdrawal(codes, new HashSet<>(deletion.failedItems()),
                    new HashSet<>(deletion.missingItems()));
        } catch (RuntimeException withdrawFailed) {
            log.warn("GetMore: could not withdraw the code(s) {} {} on prefix {}: {}", masked(codes), which, prefix,
                    GetMoreCodes.maskAll(String.valueOf(withdrawFailed.getMessage()), codes));
            return new Withdrawal(codes, new HashSet<>(codes), Set.of());
        }
    }

    /**
     * What a best-effort {@code deleteByCodes} did: the codes sent, those the store reported failed (all of them when
     * the call itself failed) and those it reported missing.
     */
    private record Withdrawal(List<String> sent, Set<String> failed, Set<String> missing) {

        static final Withdrawal NONE = new Withdrawal(List.of(), Set.of(), Set.of());

        /** Sent and not reported failed (withdrawn, or already gone), as {@code ****8B1C}. */
        List<String> withdrawnMasked() {
            return sent.stream().filter(code -> !failed.contains(code)).map(GetMoreCodes::maskedCode).toList();
        }

        /** Not confirmed deleted by this call: reported failed or missing, or the call failed. */
        boolean unconfirmed(String code) {
            return failed.contains(code) || missing.contains(code);
        }
    }

    private FindOrCreate tooMany(CouponRequest request, List<String> generated) {
        log.warn("GetMore: more than {} pages of coupons on prefix {}: parked", MAX_COUPON_PAGES, request.prefix());
        return parked(request, FindOrCreate.TOO_MANY_ON_PREFIX, "more than " + MAX_COUPON_PAGES
                + " pages of coupons on prefix " + request.prefix(), generated, Withdrawal.NONE);
    }

    /** Whether any of {@code codes} is among the coupons, without case (Magento compares codes without case). */
    private static boolean containsAny(List<GetMoreCoupon> coupons, List<String> codes) {
        return coupons.stream().anyMatch(c -> codes.stream().anyMatch(code -> code.equalsIgnoreCase(c.code())));
    }

    /** The reason a search failed, for an operator: the kind, never a code. */
    private static String reasonOf(GetMoreException failure) {
        return switch (failure) {
            case GetMoreUnavailableException unavailable -> unavailable.reason().name();
            case GetMoreRejectedException rejected -> "rejected " + rejected.reason().name();
            case GetMoreOutcomeUnknownException ignored -> "outcome unknown";
        };
    }

    private static void giveBack(GenerateGuard guard, GetMoreException notProcessed) {
        try {
            guard.notProcessed();
        } catch (RuntimeException bookkeepingFailed) {
            log.error("GetMore: the generate was not processed ({}) but its count could not be given back ({});"
                            + " the issuance keeps it", notProcessed.getMessage(),
                    bookkeepingFailed.getClass().getSimpleName());
        }
    }

    private int pageSize() {
        return properties.getPageSize();
    }

    private static String describe(List<GetMoreCoupon> coupons) {
        return coupons.stream().map(c -> c.couponId() + " (" + GetMoreCodes.maskedCode(c.code()) + ", used "
                + c.timesUsed() + ")").toList().toString();
    }

    private static String masked(Collection<String> codes) {
        return codes.stream().map(GetMoreCodes::maskedCode).toList().toString();
    }

    private static <T> List<T> sortedBy(Collection<T> items, ToLongFunction<T> id) {
        return items.stream().sorted(Comparator.comparingLong(id)).toList();
    }

    private static List<List<Long>> chunks(Collection<Long> ids, int size) {
        List<List<Long>> out = new ArrayList<>();
        List<Long> chunk = new ArrayList<>();
        for (Long id : ids) {
            chunk.add(id);
            if (chunk.size() == size) {
                out.add(List.copyOf(chunk));
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            out.add(List.copyOf(chunk));
        }
        return out;
    }
}
