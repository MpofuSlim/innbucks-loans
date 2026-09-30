package zw.co.innbucks.loans.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stored times are UTC; the market's clock applies only where a person or a calendar is involved.
 * These pin the conversions every one of those places goes through.
 */
class MarketTimeZoneTest {

    private static final MarketTimeZone HARARE = new MarketTimeZone("ZW");

    @Test
    @DisplayName("the country picks the market clock, as the ticketing services map it")
    void countryPicksTheZone() {
        assertThat(HARARE.zone()).isEqualTo(ZoneId.of("Africa/Harare"));
        assertThat(new MarketTimeZone("KE").zone()).isEqualTo(ZoneId.of("Africa/Nairobi"));
        assertThat(new MarketTimeZone(" ng ").zone()).isEqualTo(ZoneId.of("Africa/Lagos"));
    }

    @Test
    @DisplayName("an unknown or missing country refuses to start rather than falling back to UTC")
    void unknownCountryRefusesToStart() {
        assertThatThrownBy(() -> new MarketTimeZone("XX")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INNBUCKS_COUNTRY");
        assertThatThrownBy(() -> new MarketTimeZone("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MarketTimeZone(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("today is the market's day: 22:30 UTC on the 30th is already the 1st in Harare")
    void todayIsTheMarketsDay() {
        Clock lateUtc = Clock.fixed(Instant.parse("2026-09-30T22:30:00Z"), ZoneOffset.UTC);

        assertThat(new MarketTimeZone("ZW", lateUtc).today()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(new MarketTimeZone("NG", lateUtc).today()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("a market day spans 22:00 UTC the day before to 21:59:59.999999999 UTC in Harare")
    void marketDayBoundsInUtc() {
        LocalDate first = LocalDate.of(2026, 10, 1);

        assertThat(HARARE.startOfDayUtc(first)).isEqualTo(LocalDateTime.of(2026, 9, 30, 22, 0));
        assertThat(HARARE.endOfDayUtc(first)).isEqualTo(LocalDateTime.of(2026, 10, 1, 21, 59, 59, 999_999_999));
        assertThat(new MarketTimeZone("KE").startOfDayUtc(first)).isEqualTo(LocalDateTime.of(2026, 9, 30, 21, 0));
        assertThat(HARARE.startOfDayUtc(null)).isNull();
        assertThat(HARARE.endOfDayUtc(null)).isNull();
    }

    @Test
    @DisplayName("a stored UTC time is read on the market's calendar and clock")
    void storedUtcReadInTheMarket() {
        LocalDateTime utc = LocalDateTime.of(2026, 9, 30, 22, 30);

        assertThat(HARARE.localDay(utc)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(HARARE.atMarketFromUtc(utc)).hasToString("2026-10-01T00:30+02:00");
        assertThat(new MarketTimeZone("KE").atMarketFromUtc(utc)).hasToString("2026-10-01T01:30+03:00");
        assertThat(HARARE.localDay(null)).isNull();
        assertThat(HARARE.atMarketFromUtc(null)).isNull();
    }

    @Test
    @DisplayName("a stored UTC time is written for a person as the JSON carries it: market offset, whole seconds")
    void storedUtcRenderedForAPerson() {
        LocalDateTime utc = LocalDateTime.of(2026, 9, 30, 18, 22, 9, 999_999_000);

        assertThat(HARARE.render(utc)).isEqualTo("2026-09-30T20:22:09+02:00");
        assertThat(new MarketTimeZone("KE").render(utc)).isEqualTo("2026-09-30T21:22:09+03:00");
        assertThat(new MarketTimeZone("NG").render(LocalDateTime.of(2026, 9, 30, 23, 30))).isEqualTo(
                "2026-10-01T00:30:00+01:00");
        assertThat(HARARE.render(null)).isNull();
    }
}
