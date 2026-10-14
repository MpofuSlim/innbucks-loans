package zw.co.innbucks.loans.core.getmore.simulator;

/**
 * Who used a staging test action, as the audit records it: the address the request came from, what
 * {@code X-Forwarded-For} said (its first 64 characters), and the {@link SimulatorApiKey#fingerprint} of the key it
 * held. Never the key.
 */
public record SimCaller(String remoteAddress, String forwardedFor, String keyFingerprint) {

    static final int MAX_FORWARDED_FOR = 64;

    public SimCaller {
        forwardedFor = forwardedFor == null || forwardedFor.length() <= MAX_FORWARDED_FOR ? forwardedFor
                : forwardedFor.substring(0, MAX_FORWARDED_FOR);
    }

    /** {@code ip:<address>;xff:<forwarded for>;key:<fingerprint>}, for an audit row's detail. */
    String detail() {
        return "ip:" + (remoteAddress == null ? "" : remoteAddress) + ";xff:" + (forwardedFor == null ? ""
                : forwardedFor) + ";key:" + keyFingerprint;
    }
}
