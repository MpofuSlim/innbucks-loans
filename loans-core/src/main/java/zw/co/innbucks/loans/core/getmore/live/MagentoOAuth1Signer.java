package zw.co.innbucks.loans.core.getmore.live;

import zw.co.innbucks.loans.core.getmore.GetMoreProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * OAuth 1.0a with HMAC-SHA256, exactly as Magento 2.4 verifies an integration's request (RFC 5849): signed per request
 * over the exact {@link URI} sent. Hand-rolled on {@link Mac}, so the test vectors are deterministic and no dependency
 * is added.
 *
 * <ol>
 *   <li>The oauth parameters: consumer key, a fresh nonce, {@code HMAC-SHA256}, the timestamp, the token, {@code 1.0}.</li>
 *   <li>Every query parameter, split on {@code &} then the first {@code =}, percent-decoded ({@code +} stays
 *       {@code +}). A pair with no {@code =}, an empty name or a name seen twice is refused: Magento keeps only the
 *       last of a duplicate, so we would sign something it never sees. The JSON body is never signed.</li>
 *   <li>The base URL: {@code signature-base-url} when set (GetMore terminating TLS in front of Magento), else the
 *       URI's; lower-cased {@code scheme://host}, the port dropped only for http on 80 and https on 443; then the raw
 *       path, no query, no fragment.</li>
 *   <li>Every name and value RFC 3986-encoded, sorted by encoded name then encoded value, joined {@code k=v&...}.</li>
 *   <li>Base string {@code METHOD&enc(baseUrl)&enc(params)}; key {@code enc(consumerSecret)&enc(tokenSecret)};
 *       signature Base64 of HMAC-SHA256.</li>
 *   <li>Header {@code OAuth } and the seven oauth parameters sorted by name, {@code k="enc(v)"}, joined {@code ", "}.
 *       No realm.</li>
 * </ol>
 *
 * <p>A fresh nonce on every send and never an automatic replay after a 401: in Magento a 401 means bad credentials or
 * ACL, not an expired token, and a reused nonce is itself a 401. Never log the header or the base string's key.
 */
public final class MagentoOAuth1Signer {

    static final String SIGNATURE_METHOD = "HMAC-SHA256";
    private static final char[] NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    /** Magento stores a nonce in a varchar(32). */
    private static final int NONCE_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GetMoreProperties.Live live;
    private final Clock clock;
    private final Supplier<String> nonce;

    public MagentoOAuth1Signer(GetMoreProperties.Live live, Clock clock, Supplier<String> nonce) {
        this.live = live;
        this.clock = clock;
        this.nonce = nonce;
    }

    /** 32 characters of {@code [a-z0-9]} from {@link SecureRandom}. */
    public static String secureNonce() {
        char[] out = new char[NONCE_LENGTH];
        for (int i = 0; i < NONCE_LENGTH; i++) {
            out[i] = NONCE_ALPHABET[RANDOM.nextInt(NONCE_ALPHABET.length)];
        }
        return new String(out);
    }

    /** The {@code Authorization} header value for one send of {@code method uri}. */
    public String authorization(String method, URI uri) {
        return sign(method, uri).header();
    }

    /**
     * The whole signing, for the test vectors. {@code toString} shows nothing.
     *
     * @throws IllegalArgumentException for a query Magento would read differently from what is signed
     */
    Signed sign(String method, URI uri) {
        Map<String, String> oauth = new TreeMap<>();
        oauth.put("oauth_consumer_key", live.getConsumerKey());
        oauth.put("oauth_nonce", nonce.get());
        oauth.put("oauth_signature_method", SIGNATURE_METHOD);
        oauth.put("oauth_timestamp", String.valueOf(clock.instant().getEpochSecond()));
        oauth.put("oauth_token", live.getAccessToken());
        oauth.put("oauth_version", "1.0");

        List<String[]> encoded = new ArrayList<>();
        oauth.forEach((k, v) -> encoded.add(new String[]{Rfc3986.encode(k), Rfc3986.encode(v)}));
        for (Map.Entry<String, String> pair : queryPairs(uri.getRawQuery())) {
            encoded.add(new String[]{Rfc3986.encode(pair.getKey()), Rfc3986.encode(pair.getValue())});
        }
        encoded.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        StringJoiner params = new StringJoiner("&");
        encoded.forEach(p -> params.add(p[0] + "=" + p[1]));

        String baseString = method.toUpperCase(Locale.ROOT) + "&" + Rfc3986.encode(baseUrl(uri)) + "&"
                + Rfc3986.encode(params.toString());
        String key = Rfc3986.encode(live.getConsumerSecret()) + "&" + Rfc3986.encode(live.getAccessTokenSecret());
        String signature = hmacSha256(key, baseString);
        oauth.put("oauth_signature", signature);

        StringJoiner header = new StringJoiner(", ", "OAuth ", "");
        oauth.forEach((k, v) -> header.add(k + "=\"" + Rfc3986.encode(v) + "\""));
        return new Signed(baseString, signature, header.toString());
    }

    /** What one signing produced. Never printed: the header carries the consumer key and token. */
    record Signed(String baseString, String signature, String header) {
        @Override
        public String toString() {
            return "Signed[****]";
        }
    }

    /**
     * The decoded query pairs, refusing what Magento would read differently.
     *
     * @throws IllegalArgumentException a pair with no {@code =}, an empty name, a name seen twice
     */
    static List<Map.Entry<String, String>> queryPairs(String rawQuery) {
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return pairs;
        }
        Set<String> seen = new HashSet<>();
        for (String part : rawQuery.split("&", -1)) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("A query parameter without '=' cannot be signed");
            }
            String name = Rfc3986.decode(part.substring(0, eq));
            if (name.isEmpty()) {
                throw new IllegalArgumentException("A query parameter with an empty name cannot be signed");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("A query parameter named twice cannot be signed: Magento keeps "
                        + "only the last");
            }
            pairs.add(Map.entry(name, Rfc3986.decode(part.substring(eq + 1))));
        }
        return pairs;
    }

    /** The URL Magento rebuilds from its own view of the request: scheme, host, non-default port, raw path. */
    String baseUrl(URI uri) {
        String override = live.getSignatureBaseUrl();
        if (override == null || override.isBlank()) {
            return normalised(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getRawPath());
        }
        URI sent = URI.create(stripTrailingSlash(live.getBaseUrl()));
        URI signedAs = URI.create(stripTrailingSlash(override));
        String sentBase = nullToEmpty(sent.getRawPath());
        String path = nullToEmpty(uri.getRawPath());
        if (!path.startsWith(sentBase)) {
            throw new IllegalArgumentException("The request path is not under GETMORE_BASE_URL");
        }
        return normalised(signedAs.getScheme(), signedAs.getHost(), signedAs.getPort(),
                nullToEmpty(signedAs.getRawPath()) + path.substring(sentBase.length()));
    }

    private static String normalised(String scheme, String host, int port, String rawPath) {
        String s = scheme.toLowerCase(Locale.ROOT);
        boolean defaultPort = port == -1 || ("http".equals(s) && port == 80) || ("https".equals(s) && port == 443);
        return s + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port) + nullToEmpty(rawPath);
    }

    private static String hmacSha256(String key, String baseString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
