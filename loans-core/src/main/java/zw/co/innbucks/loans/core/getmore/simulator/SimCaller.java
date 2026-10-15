package zw.co.innbucks.loans.core.getmore.simulator;

/**
 * Who used a staging test action, as the audit records it: the address the request came from, what
 * {@code X-Forwarded-For} said (its LAST 64 characters), and the {@link SimulatorApiKey#fingerprint} of the key it
 * held. Never the key.
 *
 * <p>The last characters, because in the cell the request's own address is the gateway's, and the proxies (nginx, the
 * gateway) APPEND the hop they saw to the end of {@code X-Forwarded-For}: its head is whatever the client sent, and a
 * client that sent 64 characters of it would otherwise push every address a proxy wrote out of the record. Anything
 * but hex digits, {@code . : ,} and spaces (all an IPv4 or IPv6 list needs) becomes {@code _}, so no text the client
 * chose can add a field to the {@code ;}-separated audit detail.</p>
 */
public record SimCaller(String remoteAddress, String forwardedFor, String keyFingerprint) {

    static final int MAX_FORWARDED_FOR = 64;

    public SimCaller {
        if (forwardedFor != null) {
            String tail = forwardedFor.length() <= MAX_FORWARDED_FOR ? forwardedFor
                    : forwardedFor.substring(forwardedFor.length() - MAX_FORWARDED_FOR);
            forwardedFor = tail.replaceAll("[^0-9A-Fa-f.:, ]", "_");
        }
    }

    /** {@code ip:<address>;xff:<forwarded for>;key:<fingerprint>}, for an audit row's detail. */
    String detail() {
        return "ip:" + (remoteAddress == null ? "" : remoteAddress) + ";xff:" + (forwardedFor == null ? ""
                : forwardedFor) + ";key:" + keyFingerprint;
    }
}
