package zw.co.reikan.loans.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;

/**
 * The wall-clock timezone of the market this deployment serves, resolved once from
 * {@code innbucks.country} (env {@code INNBUCKS_COUNTRY}, set per cell alongside the ticketing
 * fleet), the same way the ticketing services resolve theirs.
 *
 * <p>The rule it serves: every timestamp is STORED in UTC (the container pins
 * {@code -Duser.timezone=UTC} and the code calls {@code LocalDateTime.now(ZoneOffset.UTC)}), and the
 * market's clock is applied only where a person or a calendar is involved: the timestamps the API
 * sends, and the business dates decided here (the first deduction month, a report's day and month
 * cut-offs, the year in a public reference). A ZW cell answers in Harare time because it is
 * configured as ZW, not because of whatever the server's clock is set to.</p>
 *
 * <p>An unmapped country refuses to start rather than falling back to UTC: a silent UTC fallback
 * would put every loan lodged between midnight and 02:00 in Harare on the previous day, and on the
 * 1st, in the previous month, while looking perfectly healthy. The map is the ticketing fleet's,
 * verbatim; keep them in lock-step. None of these markets observes DST, so a local time is never
 * ambiguous or missing and nothing here needs a gap/overlap policy.</p>
 */
@Slf4j
@Component
public class MarketTimeZone {

    private static final Map<String, ZoneId> ZONES = Map.ofEntries(
            Map.entry("ZW", ZoneId.of("Africa/Harare")),        // UTC+2
            Map.entry("KE", ZoneId.of("Africa/Nairobi")),       // UTC+3
            Map.entry("ZM", ZoneId.of("Africa/Lusaka")),        // UTC+2
            Map.entry("MW", ZoneId.of("Africa/Blantyre")),      // UTC+2
            Map.entry("ZA", ZoneId.of("Africa/Johannesburg")),  // UTC+2
            Map.entry("BW", ZoneId.of("Africa/Gaborone")),      // UTC+2
            Map.entry("MZ", ZoneId.of("Africa/Maputo")),        // UTC+2
            Map.entry("LS", ZoneId.of("Africa/Maseru")),        // UTC+2
            Map.entry("SZ", ZoneId.of("Africa/Mbabane")),       // UTC+2
            Map.entry("NG", ZoneId.of("Africa/Lagos"))          // UTC+1
    );

    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public MarketTimeZone(@Value("${innbucks.country:ZW}") String country) {
        this(country, Clock.systemUTC());
    }

    /** With a fixed clock, so a test can stand at 00:30 on the 1st. */
    public MarketTimeZone(String country, Clock clock) {
        String key = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        ZoneId resolved = ZONES.get(key);
        if (resolved == null) {
            throw new IllegalStateException("innbucks.country='" + country + "' has no market timezone mapping."
                    + " Known: " + ZONES.keySet() + " - set INNBUCKS_COUNTRY to one of them");
        }
        this.zone = resolved;
        this.clock = clock;
        log.info("[startup] loans pinned to country={} (market time {}); timestamps are stored in UTC", key, resolved);
    }

    /** This deployment's market timezone. */
    public ZoneId zone() {
        return zone;
    }

    /** The market's calendar day right now: what "today" means for a deduction month or a report. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** The market's calendar day of a stored UTC timestamp. Null passes through. */
    public LocalDate localDay(LocalDateTime utc) {
        return utc == null ? null : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate();
    }

    /**
     * The first instant of a market-local day, as the UTC timestamp the columns store, for the lower
     * bound of a date filter. Null passes through, so an absent filter stays absent.
     */
    public LocalDateTime startOfDayUtc(LocalDate marketDay) {
        return marketDay == null ? null
                : marketDay.atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** The last instant of a market-local day, as a stored UTC timestamp, for an inclusive upper bound. */
    public LocalDateTime endOfDayUtc(LocalDate marketDay) {
        return marketDay == null ? null
                : marketDay.atTime(LocalTime.MAX).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * A stored UTC timestamp re-expressed on the market's clock, offset included: {@code 06:10:22}
     * stored is {@code 08:10:22+02:00} for a ZW cell. Same instant, but the digits a person reads
     * are their own clock's. Null passes through.
     */
    public OffsetDateTime atMarketFromUtc(LocalDateTime utc) {
        return utc == null ? null : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toOffsetDateTime();
    }
}
