package zw.co.innbucks.loans.core.getmore.live.dto;

/**
 * A 2xx body that is not the shape asked for: a required key missing, or a value that cannot be what Magento defines.
 * The message names the field, never its value.
 */
public final class MagentoShapeException extends RuntimeException {

    public MagentoShapeException(String message) {
        super(message, null, false, false);
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new MagentoShapeException("missing " + field);
        }
        return value;
    }
}
