package zw.co.innbucks.loans.core.getmore;

/**
 * The durable bookkeeping around the one GetMore write that can mint a coupon. Magento's generate has no idempotency
 * key, so whether one may reach the store is counted in loans' database BEFORE the POST, and given back only when the
 * POST was provably not processed. Called with no transaction open; each method runs in a transaction of its own.
 */
public interface GenerateGuard {

    /**
     * Counts the generate about to be sent, before it is sent.
     *
     * @throws ClaimLostException          this attempt no longer holds the claim: nothing is sent
     * @throws GenerateNotAllowedException the allowance is used up: nothing is sent
     */
    void beforeGenerate();

    /**
     * The POST was provably not processed ({@link GetMoreRejectedException} or {@link GetMoreUnavailableException}):
     * gives the count back.
     */
    void notProcessed();
}
