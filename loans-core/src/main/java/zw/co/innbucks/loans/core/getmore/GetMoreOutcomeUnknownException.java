package zw.co.innbucks.loans.core.getmore;

import java.util.List;

/**
 * A write (generate, PUT or deleteByCodes) may have applied: it was sent and its answer was lost, unreadable or a
 * server error. Never blind-retried. For a generate, search the prefix before anything else: the coupon may exist.
 *
 * <p>{@link #mintedCodes()} holds full codes and is non-empty only when a generate returned codes that the follow-up
 * search could not find; the message and {@code toString} never show them, only how many there were.
 */
public final class GetMoreOutcomeUnknownException extends GetMoreException {

    private final List<String> mintedCodes;

    public GetMoreOutcomeUnknownException(Operation operation, String detail) {
        this(operation, List.of(), detail);
    }

    /** @param detail masked text for the operator, or null */
    public GetMoreOutcomeUnknownException(Operation operation, List<String> mintedCodes, String detail) {
        super(operation, label(operation) + ": the outcome is unknown"
                + (mintedCodes == null || mintedCodes.isEmpty() ? ""
                        : " (" + mintedCodes.size() + " minted code(s) not found by search)")
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.mintedCodes = mintedCodes == null ? List.of() : List.copyOf(mintedCodes);
    }

    /** Codes a generate returned that a search could not find, exactly as returned. Never log them. */
    public List<String> mintedCodes() {
        return mintedCodes;
    }
}
