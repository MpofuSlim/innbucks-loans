package zw.co.innbucks.loans.core.getmore.simulator;

/**
 * A staging test action's answer: what it did in words, whether it was a repeat answered as before (a sale or refund
 * under a reference already used for it), and the body.
 */
public record SimAction<T>(String message, boolean replayed, T data) {
}
