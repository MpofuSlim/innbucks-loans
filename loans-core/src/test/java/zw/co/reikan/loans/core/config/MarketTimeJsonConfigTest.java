package zw.co.reikan.loans.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API used to send a bare {@code 2026-09-30T22:30:15.123456}: no zone, so a reader could not tell
 * whose clock it was on. It now sends the stored UTC instant at the market's offset, as the ticketing
 * services do, and reads back any of the three forms a client may send.
 */
class MarketTimeJsonConfigTest {

    private static JsonMapper mapper(String country) {
        return JsonMapper.builder()
                .addModule(new MarketTimeJsonConfig().marketLocalDateTimeModule(new MarketTimeZone(country)))
                .build();
    }

    @Test
    @DisplayName("a stored UTC time goes out on the market's clock with its offset, in whole seconds")
    void writtenAtTheMarketOffset() {
        LocalDateTime storedUtc = LocalDateTime.of(2026, 9, 30, 22, 30, 15, 123_456_000);

        assertThat(mapper("ZW").writeValueAsString(Map.of("at", storedUtc)))
                .isEqualTo("{\"at\":\"2026-10-01T00:30:15+02:00\"}");
        assertThat(mapper("KE").writeValueAsString(Map.of("at", storedUtc)))
                .isEqualTo("{\"at\":\"2026-10-01T01:30:15+03:00\"}");
    }

    @Test
    @DisplayName("an offset or Z is read back to the UTC the columns hold; a bare value is taken as UTC")
    void readBackToUtc() {
        JsonMapper mapper = mapper("ZW");
        LocalDateTime utc = LocalDateTime.of(2026, 9, 30, 22, 30, 15);

        assertThat(mapper.readValue("\"2026-10-01T00:30:15+02:00\"", LocalDateTime.class)).isEqualTo(utc);
        assertThat(mapper.readValue("\"2026-09-30T22:30:15Z\"", LocalDateTime.class)).isEqualTo(utc);
        assertThat(mapper.readValue("\"2026-09-30T22:30:15\"", LocalDateTime.class)).isEqualTo(utc);
        assertThat(mapper.readValue("\"\"", LocalDateTime.class)).isNull();
    }

    @Test
    @DisplayName("what goes out comes back as the same stored time")
    void roundTrip() {
        JsonMapper mapper = mapper("ZW");
        LocalDateTime storedUtc = LocalDateTime.of(2026, 1, 1, 0, 15, 0);

        String wire = mapper.writeValueAsString(storedUtc);

        assertThat(wire).isEqualTo("\"2026-01-01T02:15:00+02:00\"");
        assertThat(mapper.readValue(wire, LocalDateTime.class)).isEqualTo(storedUtc);
    }
}
