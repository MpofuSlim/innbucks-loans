package zw.co.innbucks.loans.core.disbursements;

/**
 * What became of one pre-approved booking ({@link InnbucksDisbursementService#bookPreApproved}), unclassified: the
 * caller decides what it means for its loan. Either InnBucks answered, with {@code httpStatus} and its answer, or the
 * call failed with {@code failure} and no answer was read.
 *
 * <p>A {@link BookingNotSentException} failure, and only that, proves the booking never reached InnBucks: the login
 * failed, the connection was never opened, or the request could not be built ({@link BookingNotBuiltException}). Every
 * other failure (a read timeout, a reset, an unexpected exception) may have booked it.
 *
 * @param httpStatus   the HTTP status InnBucks answered, after the one replay a 401 gets; 0 when it did not answer
 * @param responseCode the {@code responseCode} of InnBucks' envelope, as text ({@code 000}); null when the answer
 *                     carried none (no body, not a JSON object, or no such field)
 * @param answer       its answer, redacted and cut to {@link BankAnswers#MAX_CHARS}; null when there was none
 * @param failure      why no answer was read; null when InnBucks answered
 */
public record PreApprovedBookingResult(int httpStatus, String responseCode, String answer, RuntimeException failure) {

    static PreApprovedBookingResult answered(int httpStatus, String responseCode, String answer) {
        return new PreApprovedBookingResult(httpStatus, responseCode, answer, null);
    }

    static PreApprovedBookingResult failed(RuntimeException failure) {
        return new PreApprovedBookingResult(0, null, null, failure);
    }

    /** InnBucks answered (any status). */
    public boolean wasAnswered() {
        return failure == null;
    }

    /** It provably never reached InnBucks. */
    public boolean notSent() {
        return failure instanceof BookingNotSentException;
    }

    /** It was never sent because the request could not be built: a reason of this booking's own, not the bank's. */
    public boolean notBuilt() {
        return failure instanceof BookingNotBuiltException;
    }

    /**
     * Whether InnBucks' envelope says it went through: {@code responseCode} 000 (any number of zeros, as a string or a
     * number). False for an answer with no envelope, and for any other code.
     */
    public boolean approved() {
        return responseCode != null && !responseCode.isEmpty() && responseCode.chars().allMatch(c -> c == '0');
    }

    /** Never prints the answer: it names the borrower. */
    @Override
    public String toString() {
        return "PreApprovedBookingResult[httpStatus=" + httpStatus + ", responseCode=" + responseCode
                + (failure == null ? "" : ", failure=" + failure.getClass().getSimpleName()) + "]";
    }
}
