package zw.co.reikan.loans.core.notifications;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the external WhatsApp notification gateway. It is a third-party
 * service reached over an explicit {@code base-url} and authenticated with an
 * {@code x-api-key} header — mirrors the ticketing platform's {@code whatsapp.*}
 * config so the same env values ({@code WHATSAPP_GATEWAY_URL},
 * {@code WHATSAPP_API_KEY}) configure both systems.
 */
@Data
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {
    /** Gateway base URL, e.g. https://gateway.example.com (no trailing path). */
    private String baseUrl;
    /** Secret presented as the {@code x-api-key} header on every call. */
    private String apiKey;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}
