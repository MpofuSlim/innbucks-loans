package zw.co.innbucks.loans.core.getmore;

/**
 * Which {@link GetMoreStore} this host talks to ({@code GETMORE_MODE}). Chosen once, at boot, by configuration; the code
 * above the port is the same in every mode, so going live is a configuration change.
 */
public enum GetMoreMode {
    /** The default: nothing calls GetMore. */
    OFF,
    /** GetMore's Magento 2.4 store, over OAuth 1.0a (HMAC-SHA256). */
    LIVE,
    /** Staging only: an in-process stand-in store with its own tables. */
    SIMULATED
}
