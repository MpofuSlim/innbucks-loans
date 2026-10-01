package zw.co.innbucks.loans.core.voucher;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.CurrencyTotals;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.Event;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.Line;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.OutletTotals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The daily settlement and reconciliation report (FR-SGL-038): one market day's vouchers issued, redeemed by outlet,
 * cancelled and lapsed, as JSON or as a CSV of every event. Codes are masked (FR-SGL-040).
 */
@Service
@RequiredArgsConstructor
public class VoucherSettlementService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final String[] CSV_HEADER = {"event", "at", "voucherId", "maskedCode", "loanAccount",
            "customerReference", "currency", "amount", "outletId", "outletName", "merchantReference"};

    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository redemptionRepository;
    private final MarketTimeZone marketTimeZone;

    /** The report for a market day, today's so far included; a day still to come is refused. */
    @Transactional(readOnly = true)
    public VoucherSettlementReport report(LocalDate day) {
        if (day.isAfter(marketTimeZone.today())) {
            throw new ValidationException("date (" + day + ") is in the future");
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        LocalDateTime from = marketTimeZone.startOfDayUtc(day);
        LocalDateTime to = marketTimeZone.endOfDayUtc(day);
        List<Line> lines = new ArrayList<>();
        voucherRepository.findByIssuedAtBetweenOrderByIdAsc(from, to).forEach(voucher ->
                lines.add(line(Event.ISSUED, voucher.getIssuedAt(), voucher, voucher.getFaceValue())));
        List<VoucherRedemption> redemptions = redemptionRepository.findByRedeemedAtBetweenOrderByIdAsc(from, to);
        Map<Long, Voucher> redeemed = voucherRepository.findAllById(redemptions.stream()
                        .map(VoucherRedemption::getVoucherId).distinct().toList()).stream()
                .collect(Collectors.toMap(Voucher::getId, Function.identity()));
        redemptions.forEach(redemption -> {
            Voucher voucher = redeemed.get(redemption.getVoucherId());
            lines.add(new Line(Event.REDEEMED, redemption.getRedeemedAt(), voucher.getId(), voucher.maskedCode(),
                    voucher.getLoanAccount(), voucher.getCustomerReference(), voucher.getCurrency(),
                    redemption.getAmount(), redemption.getOutletId(), redemption.getOutletName(),
                    redemption.getMerchantReference()));
        });
        voucherRepository.findByCancelledAtBetweenOrderByIdAsc(from, to).forEach(voucher ->
                lines.add(line(Event.CANCELLED, voucher.getCancelledAt(), voucher, voucher.getFaceValue())));
        voucherRepository.findByExpiresAtBetweenAndStatusInOrderByIdAsc(from, to, EnumSet.of(VoucherStatus.EXPIRED,
                        VoucherStatus.ISSUED, VoucherStatus.PARTIALLY_REDEEMED)).stream()
                .filter(voucher -> voucher.lapsed(now))
                .forEach(voucher -> lines.add(line(Event.EXPIRED, voucher.getExpiresAt(), voucher, voucher.balance())));
        lines.sort(Comparator.comparing(Line::at).thenComparing(Line::voucherId));
        return new VoucherSettlementReport(day, now, totals(lines), byOutlet(lines), lines);
    }

    /** The report's events as CSV, one row each, for a spreadsheet. */
    @Transactional(readOnly = true)
    public String csv(LocalDate day) {
        StringBuilder out = new StringBuilder(String.join(",", CSV_HEADER)).append("\r\n");
        for (Line line : report(day).lines()) {
            out.append(String.join(",", line.event().name(), marketTimeZone.render(line.at()),
                            String.valueOf(line.voucherId()), cell(line.maskedCode()), cell(line.loanAccount()),
                            cell(line.customerReference()), line.currency(), line.amount().toPlainString(),
                            cell(line.outletId()), cell(line.outletName()), cell(line.merchantReference())))
                    .append("\r\n");
        }
        return out.toString();
    }

    private static Line line(Event event, LocalDateTime at, Voucher voucher, BigDecimal amount) {
        return new Line(event, at, voucher.getId(), voucher.maskedCode(), voucher.getLoanAccount(),
                voucher.getCustomerReference(), voucher.getCurrency(), amount, null, null, null);
    }

    private static List<CurrencyTotals> totals(List<Line> lines) {
        Map<String, List<Line>> byCurrency = lines.stream()
                .collect(Collectors.groupingBy(Line::currency, TreeMap::new, Collectors.toList()));
        List<CurrencyTotals> totals = new ArrayList<>();
        byCurrency.forEach((currency, events) -> totals.add(new CurrencyTotals(currency,
                count(events, Event.ISSUED), sum(events, Event.ISSUED),
                count(events, Event.REDEEMED), sum(events, Event.REDEEMED),
                count(events, Event.CANCELLED), sum(events, Event.CANCELLED),
                count(events, Event.EXPIRED), sum(events, Event.EXPIRED))));
        return totals;
    }

    private static List<OutletTotals> byOutlet(List<Line> lines) {
        Map<List<String>, List<Line>> grouped = new LinkedHashMap<>();
        lines.stream().filter(line -> line.event() == Event.REDEEMED)
                .sorted(Comparator.comparing(Line::outletId).thenComparing(Line::currency))
                .forEach(line -> grouped.computeIfAbsent(List.of(line.outletId(), line.currency()),
                        key -> new ArrayList<>()).add(line));
        List<OutletTotals> totals = new ArrayList<>();
        grouped.forEach((key, redemptions) -> totals.add(new OutletTotals(key.get(0),
                redemptions.getLast().outletName(), key.get(1), redemptions.size(),
                sum(redemptions, Event.REDEEMED))));
        return totals;
    }

    private static long count(List<Line> lines, Event event) {
        return lines.stream().filter(line -> line.event() == event).count();
    }

    private static BigDecimal sum(List<Line> lines, Event event) {
        return lines.stream().filter(line -> line.event() == event).map(Line::amount).reduce(ZERO, BigDecimal::add);
    }

    /**
     * A field made safe for a spreadsheet: quoted when it holds a comma, quote or line break, and with a leading
     * {@code = + - @} neutralised, so a value GetMore typed cannot run as a formula when the file is opened.
     */
    static String cell(String value) {
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
