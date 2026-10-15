package zw.co.innbucks.loans.core.getmore.live;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.getmore.live.dto.MagentoSearchResultDto;

/**
 * The client's own JSON mapper: unknown keys ignored (Magento sends far more than loans reads), and every JSON number
 * with a fraction read as a {@link java.math.BigDecimal}, never through a double, so {@code -45.5000} stays exactly
 * that.
 */
public final class MagentoJson {

    static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private MagentoJson() {
    }

    public static <T> T read(byte[] body, Class<T> type) {
        return JSON.readValue(body, type);
    }

    public static <T> T read(byte[] body, JavaType type) {
        return JSON.readValue(body, type);
    }

    public static JsonNode tree(byte[] body) {
        return JSON.readTree(body);
    }

    public static byte[] write(Object value) {
        return JSON.writeValueAsBytes(value);
    }

    /** {@code MagentoSearchResultDto<itemType>}. */
    public static JavaType searchResultOf(Class<?> itemType) {
        return JSON.getTypeFactory().constructParametricType(MagentoSearchResultDto.class, itemType);
    }
}
