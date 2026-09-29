package zw.co.reikan.loans.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * The wire format of every timestamp this API returns, the same rule the ticketing services follow:
 * the backend renders, the frontend parses nothing.
 *
 * <p>Each {@code LocalDateTime} here is stored in UTC and goes out at the market's offset,
 * {@code 2026-09-29T20:15:00+02:00} for a ZW cell, so the portal prints the string as it arrives and
 * does no timezone arithmetic of its own. It is the same instant as {@code 18:15:00Z}, and just as
 * unambiguous, but the leading characters are the clock of the person reading it. Before this the
 * API sent a bare {@code 2026-09-29T18:15:00.123456}: no zone at all, so a reader could not tell
 * whose clock it was on.</p>
 *
 * <p>Inbound is permissive: {@code Z}, an offset (normalised to the UTC the columns hold) or a bare
 * value (taken as UTC, as before) all parse. Only the HTTP mapper is affected: the clients that call
 * InnBucks and Ndasenda build their own converters, so what goes to the partners is unchanged.</p>
 */
@Configuration
public class MarketTimeJsonConfig {

    /** Whole seconds with the offset: {@code yyyy-MM-dd'T'HH:mm:ss+02:00}. */
    private static final DateTimeFormatter MARKET_WIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** Collected by Boot's Jackson 3 auto-configuration into the mapper the HTTP converters use. */
    @Bean
    public JacksonModule marketLocalDateTimeModule(MarketTimeZone marketTimeZone) {
        SimpleModule module = new SimpleModule("market-local-date-time");
        module.addSerializer(LocalDateTime.class, new MarketLocalDateTimeSerializer(marketTimeZone));
        module.addDeserializer(LocalDateTime.class, new FlexibleUtcLocalDateTimeDeserializer());
        return module;
    }

    static final class MarketLocalDateTimeSerializer extends ValueSerializer<LocalDateTime> {

        private final MarketTimeZone marketTimeZone;

        MarketLocalDateTimeSerializer(MarketTimeZone marketTimeZone) {
            this.marketTimeZone = marketTimeZone;
        }

        @Override
        public void serialize(LocalDateTime utc, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(MARKET_WIRE.format(marketTimeZone.atMarketFromUtc(utc.truncatedTo(ChronoUnit.SECONDS))));
        }
    }

    static final class FlexibleUtcLocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            String value = p.getString();
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            } catch (DateTimeParseException notOffset) {
                return LocalDateTime.parse(value);
            }
        }
    }
}
