package zw.co.innbucks.loans.core.voucher;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.CurrencyTotals;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.Event;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.Line;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.MerchantTotals;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.OutletTotals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The daily settlement and reconciliation report (FR-SGL-038): one market day's vouchers issued, redeemed by outlet,
 * refunded, cancelled and lapsed, as JSON or as a CSV of every event, for every merchant or for one. Codes are masked
 * (FR-SGL-040).
 *
 * <p>A till's redemption counts on the day it was made. A GetMore sale or refund counts on the day loans read it back
 * from GetMore ({@code recorded_at}), with GetMore's own time beside it, so a day already reported never changes when a
 * sync runs late: what was missed lands on the day it was read. A simulated GetMore voucher (staging) is reported only
 * on a host whose store is SIMULATED, and the report then says so; anywhere else it is left out.</p>
 */
@Service
@RequiredArgsConstructor
public class VoucherSettlementService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    /** The outlet a refund is counted at when the sale it gives back is not on record. */
    static final String UNKNOWN_OUTLET = "store-unknown";
    private static final String[] CSV_HEADER = {"event", "at", "voucherId", "maskedCode", "loanAccount",
            "customerReference", "currency", "amount", "outletId", "outletName", "merchantReference", "merchantCode",
            "occurredAt"};

    private final VoucherRepository voucherRepository;
    private final MerchantRepository merchantRepository;
    private final VoucherRedemptionRepository redemptionRepository;
    private final VoucherRefundRepository refundRepository;
    private final MarketTimeZone marketTimeZone;
    private final GetMoreProperties getMoreProperties;

    /**
     * The report for a market day, today's so far included; a day still to come is refused.
     *
     * @param merchantCode the one merchant to report on; blank for every merchant
     * @throws NotFoundException no merchant has {@code merchantCode}
     */
    @Transactional(readOnly = true)
    public VoucherSettlementReport report(LocalDate day, String merchantCode) {
        if (day.isAfter(marketTimeZone.today())) {
            throw new ValidationException("date (" + day + ") is in the future");
        }
        String merchant = StringUtils.trimToNull(merchantCode);
        if (merchant != null && !merchantRepository.existsByMerchantCode(merchant)) {
            throw new NotFoundException("Merchant " + merchant + " not found");
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        LocalDateTime from = marketTimeZone.startOfDayUtc(day);
        LocalDateTime to = marketTimeZone.endOfDayUtc(day);
        boolean simulatedHost = getMoreProperties.getMode() == GetMoreMode.SIMULATED;
        List<Line> lines = new ArrayList<>();
        Map<String, String> merchantNames = new TreeMap<>();
        voucherRepository.findByIssuedAtBetweenOrderByIdAsc(from, to).stream()
                .filter(voucher -> reported(voucher, simulatedHost))
                .forEach(voucher -> lines.add(line(Event.ISSUED, voucher.getIssuedAt(), voucher,
                        voucher.getFaceValue(), merchantNames)));
        List<VoucherRedemption> redemptions = new ArrayList<>(redemptionRepository
                .findBySourceAndRedeemedAtBetweenOrderByIdAsc(VoucherRedemptionSource.TILL, from, to));
        redemptions.addAll(redemptionRepository.findBySourceAndRecordedAtBetweenOrderByIdAsc(
                VoucherRedemptionSource.GETMORE_SYNC, from, to));
        List<VoucherRefund> refunds = refundRepository.findByRecordedAtBetweenOrderByIdAsc(from, to);
        Set<Long> voucherIds = new TreeSet<>();
        redemptions.forEach(redemption -> voucherIds.add(redemption.getVoucherId()));
        refunds.forEach(refund -> voucherIds.add(refund.getVoucherId()));
        Map<Long, Voucher> vouchers = voucherIds.isEmpty() ? Map.of() : voucherRepository.findAllById(voucherIds)
                .stream().collect(Collectors.toMap(Voucher::getId, Function.identity()));
        redemptions.forEach(redemption -> {
            Voucher voucher = vouchers.get(redemption.getVoucherId());
            if (!reported(voucher, simulatedHost)) {
                return;
            }
            boolean synced = redemption.getSource() == VoucherRedemptionSource.GETMORE_SYNC;
            lines.add(new Line(Event.REDEEMED, synced ? redemption.getRecordedAt() : redemption.getRedeemedAt(),
                    synced ? redemption.getRedeemedAt() : null, voucher.getId(), named(voucher, merchantNames),
                    voucher.maskedCode(), voucher.getLoanAccount(), voucher.getCustomerReference(),
                    voucher.getCurrency(), redemption.getAmount(), redemption.getOutletId(),
                    redemption.getOutletName(), redemption.getMerchantReference()));
        });
        Map<List<Long>, VoucherRedemption> saleOfOrder = salesOfRefundedOrders(refunds);
        refunds.forEach(refund -> {
            Voucher voucher = vouchers.get(refund.getVoucherId());
            if (!reported(voucher, simulatedHost)) {
                return;
            }
            VoucherRedemption sale = saleOfOrder.get(List.of(refund.getVoucherId(), refund.getExternalOrderId()));
            lines.add(new Line(Event.REFUNDED, refund.getRecordedAt(), refund.getRefundedAt(), voucher.getId(),
                    named(voucher, merchantNames), voucher.maskedCode(), voucher.getLoanAccount(),
                    voucher.getCustomerReference(), voucher.getCurrency(), refund.getAmount(),
                    sale == null ? UNKNOWN_OUTLET : sale.getOutletId(), sale == null ? null : sale.getOutletName(),
                    refund.getMerchantReference()));
        });
        voucherRepository.findByCancelledAtBetweenOrderByIdAsc(from, to).stream()
                .filter(voucher -> reported(voucher, simulatedHost))
                .forEach(voucher -> lines.add(line(Event.CANCELLED, voucher.getCancelledAt(), voucher,
                        voucher.getFaceValue(), merchantNames)));
        voucherRepository.findByExpiresAtBetweenAndStatusInOrderByIdAsc(from, to, EnumSet.of(VoucherStatus.EXPIRED,
                        VoucherStatus.ISSUED, VoucherStatus.PARTIALLY_REDEEMED)).stream()
                .filter(voucher -> reported(voucher, simulatedHost) && voucher.lapsed(now))
                .forEach(voucher -> lines.add(line(Event.EXPIRED, voucher.getExpiresAt(), voucher, voucher.balance(),
                        merchantNames)));
        if (merchant != null) {
            lines.removeIf(line -> !merchant.equals(line.merchantCode()));
        }
        lines.sort(Comparator.comparing(Line::at).thenComparing(Line::voucherId));
        return new VoucherSettlementReport(day, merchant, now, totals(lines), byMerchant(lines, merchantNames),
                byOutlet(lines), lines, simulatedHost ? Boolean.TRUE : null);
    }

    /**
     * Whether a voucher belongs in this host's report: a simulated GetMore voucher only where the store is SIMULATED
     * (a staging test's rows must never reach a real settlement).
     */
    private static boolean reported(Voucher voucher, boolean simulatedHost) {
        return voucher != null && (voucher.getStoreMode() != GetMoreMode.SIMULATED || simulatedHost);
    }

    /** The first recorded sale of each refunded order, by voucher and order: the outlet a refund is counted at. */
    private Map<List<Long>, VoucherRedemption> salesOfRefundedOrders(List<VoucherRefund> refunds) {
        if (refunds.isEmpty()) {
            return Map.of();
        }
        Map<List<Long>, VoucherRedemption> sales = new HashMap<>();
        redemptionRepository.findByVoucherIdInOrderByIdAsc(refunds.stream().map(VoucherRefund::getVoucherId)
                        .collect(Collectors.toCollection(TreeSet::new))).stream()
                .filter(sale -> sale.getExternalOrderId() != null)
                .forEach(sale -> sales.putIfAbsent(List.of(sale.getVoucherId(), sale.getExternalOrderId()), sale));
        return sales;
    }

    /** The report's events as CSV, one row each, for a spreadsheet. */
    @Transactional(readOnly = true)
    public String csv(LocalDate day, String merchantCode) {
        StringBuilder out = new StringBuilder(String.join(",", CSV_HEADER)).append("\r\n");
        for (Line line : report(day, merchantCode).lines()) {
            out.append(String.join(",", line.event().name(), marketTimeZone.render(line.at()),
                            String.valueOf(line.voucherId()), cell(line.maskedCode()), cell(line.loanAccount()),
                            cell(line.customerReference()), line.currency(), line.amount().toPlainString(),
                            cell(line.outletId()), cell(line.outletName()), cell(line.merchantReference()),
                            cell(line.merchantCode()),
                            line.occurredAt() == null ? "" : marketTimeZone.render(line.occurredAt())))
                    .append("\r\n");
        }
        return out.toString();
    }

    private static Line line(Event event, LocalDateTime at, Voucher voucher, BigDecimal amount,
                             Map<String, String> merchantNames) {
        return new Line(event, at, null, voucher.getId(), named(voucher, merchantNames), voucher.maskedCode(),
                voucher.getLoanAccount(), voucher.getCustomerReference(), voucher.getCurrency(), amount, null, null,
                null);
    }

    /** The voucher's merchant code, its name noted for the per-merchant totals. */
    private static String named(Voucher voucher, Map<String, String> merchantNames) {
        String code = voucher.getMerchant().getMerchantCode();
        merchantNames.putIfAbsent(code, voucher.getMerchant().getCompanyName());
        return code;
    }

    private static List<CurrencyTotals> totals(List<Line> lines) {
        Map<String, List<Line>> byCurrency = lines.stream()
                .collect(Collectors.groupingBy(Line::currency, TreeMap::new, Collectors.toList()));
        List<CurrencyTotals> totals = new ArrayList<>();
        byCurrency.forEach((currency, events) -> totals.add(new CurrencyTotals(currency,
                count(events, Event.ISSUED), sum(events, Event.ISSUED),
                count(events, Event.REDEEMED), sum(events, Event.REDEEMED),
                count(events, Event.REFUNDED), sum(events, Event.REFUNDED), net(events),
                count(events, Event.CANCELLED), sum(events, Event.CANCELLED),
                count(events, Event.EXPIRED), sum(events, Event.EXPIRED))));
        return totals;
    }

    private static List<MerchantTotals> byMerchant(List<Line> lines, Map<String, String> merchantNames) {
        Map<List<String>, List<Line>> grouped = new LinkedHashMap<>();
        lines.stream().sorted(Comparator.comparing(Line::merchantCode).thenComparing(Line::currency))
                .forEach(line -> grouped.computeIfAbsent(List.of(line.merchantCode(), line.currency()),
                        key -> new ArrayList<>()).add(line));
        List<MerchantTotals> totals = new ArrayList<>();
        grouped.forEach((key, events) -> totals.add(new MerchantTotals(key.get(0), merchantNames.get(key.get(0)),
                key.get(1), count(events, Event.ISSUED), sum(events, Event.ISSUED),
                count(events, Event.REDEEMED), sum(events, Event.REDEEMED),
                count(events, Event.REFUNDED), sum(events, Event.REFUNDED), net(events),
                count(events, Event.CANCELLED), sum(events, Event.CANCELLED),
                count(events, Event.EXPIRED), sum(events, Event.EXPIRED))));
        return totals;
    }

    private static List<OutletTotals> byOutlet(List<Line> lines) {
        Map<List<String>, List<Line>> grouped = new LinkedHashMap<>();
        lines.stream().filter(line -> line.event() == Event.REDEEMED || line.event() == Event.REFUNDED)
                .sorted(Comparator.comparing(Line::merchantCode).thenComparing(Line::outletId)
                        .thenComparing(Line::currency))
                .forEach(line -> grouped.computeIfAbsent(List.of(line.merchantCode(), line.outletId(),
                        line.currency()), key -> new ArrayList<>()).add(line));
        List<OutletTotals> totals = new ArrayList<>();
        grouped.forEach((key, events) -> totals.add(new OutletTotals(key.get(0), key.get(1), outletName(events),
                key.get(2), count(events, Event.REDEEMED), sum(events, Event.REDEEMED),
                count(events, Event.REFUNDED), sum(events, Event.REFUNDED), net(events))));
        return totals;
    }

    /** The outlet's name as its last purchase that day gave it, or its last refund when it had no purchase. */
    private static String outletName(List<Line> events) {
        List<Line> purchases = events.stream().filter(line -> line.event() == Event.REDEEMED).toList();
        return (purchases.isEmpty() ? events : purchases).getLast().outletName();
    }

    /** What was redeemed less what was refunded. */
    private static BigDecimal net(List<Line> lines) {
        return sum(lines, Event.REDEEMED).subtract(sum(lines, Event.REFUNDED));
    }

    private static long count(List<Line> lines, Event event) {
        return lines.stream().filter(line -> line.event() == event).count();
    }

    private static BigDecimal sum(List<Line> lines, Event event) {
        return lines.stream().filter(line -> line.event() == event).map(Line::amount).reduce(ZERO, BigDecimal::add);
    }

    /**
     * A field made safe for a spreadsheet: quoted when it holds a comma, quote or line break, and with a leading
     * {@code = + - @} neutralised, so a value a merchant's till typed cannot run as a formula when the file is opened.
     */
    public static String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
