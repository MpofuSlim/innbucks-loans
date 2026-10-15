package zw.co.innbucks.loans.core.getmore.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OAuth 1.0a HMAC-SHA256 as Magento 2.4 verifies an integration's request. The two vectors are the spec's worked
 * examples (fake, test-only credentials), recomputed independently by a line-by-line port of Magento 2.4.8's verifier
 * and an RFC 5849 signer: the base string, the signature and the header must match byte for byte.
 */
class MagentoOAuth1SignerTest {

    static final String CONSUMER_KEY = "ck0123456789abcdefghijklmnopqrst";
    static final String CONSUMER_SECRET = "cs0123456789abcdefghijklmnopqrst";
    static final String TOKEN = "tk0123456789abcdefghijklmnopqrst";
    static final String TOKEN_SECRET = "ts0123456789abcdefghijklmnopqrst";
    static final String NONCE = "n0n4e0123456789abcdefghijklmnopq";
    static final long TIMESTAMP = 1760430600L;
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(TIMESTAMP), ZoneOffset.UTC);

    static GetMoreProperties.Live live() {
        GetMoreProperties.Live live = new GetMoreProperties.Live();
        live.setBaseUrl("https://qa.getmore.syncrony.com/rest/V1");
        live.setConsumerKey(CONSUMER_KEY);
        live.setConsumerSecret(CONSUMER_SECRET);
        live.setAccessToken(TOKEN);
        live.setAccessTokenSecret(TOKEN_SECRET);
        return live;
    }

    static MagentoOAuth1Signer fixedSigner(GetMoreProperties.Live live) {
        return new MagentoOAuth1Signer(live, CLOCK, () -> NONCE);
    }

    private final MagentoOAuth1Signer signer = fixedSigner(live());

    @Test
    @DisplayName("worked example 1: POST coupons/generate signs to the spec's base string, signature and header")
    void generateVector() {
        MagentoOAuth1Signer.Signed signed = signer.sign("POST",
                URI.create("https://qa.getmore.syncrony.com/rest/V1/coupons/generate"));

        assertThat(signed.baseString()).isEqualTo("POST&https%3A%2F%2Fqa.getmore.syncrony.com%2Frest%2FV1%2Fcoupons"
                + "%2Fgenerate&oauth_consumer_key%3Dck0123456789abcdefghijklmnopqrst%26oauth_nonce%3Dn0n4e0123456789"
                + "abcdefghijklmnopq%26oauth_signature_method%3DHMAC-SHA256%26oauth_timestamp%3D1760430600%26oauth_"
                + "token%3Dtk0123456789abcdefghijklmnopqrst%26oauth_version%3D1.0");
        assertThat(signed.signature()).isEqualTo("j3EDqMXSXCI0+GKWGIyLd140drbngWcEqeNbkPA02Mw=");
        assertThat(signed.header()).isEqualTo("OAuth oauth_consumer_key=\"ck0123456789abcdefghijklmnopqrst\", "
                + "oauth_nonce=\"n0n4e0123456789abcdefghijklmnopq\", "
                + "oauth_signature=\"j3EDqMXSXCI0%2BGKWGIyLd140drbngWcEqeNbkPA02Mw%3D\", "
                + "oauth_signature_method=\"HMAC-SHA256\", oauth_timestamp=\"1760430600\", "
                + "oauth_token=\"tk0123456789abcdefghijklmnopqrst\", oauth_version=\"1.0\"");
        assertThat(signer.authorization("POST", URI.create("https://qa.getmore.syncrony.com/rest/V1/coupons/generate")))
                .isEqualTo(signed.header());
    }

    @Test
    @DisplayName("worked example 2: a coupons/search query is signed over its decoded pairs; '%' is encoded twice")
    void searchVector() {
        String query = "searchCriteria%5Bfilter_groups%5D%5B0%5D%5Bfilters%5D%5B0%5D%5Bfield%5D=code"
                + "&searchCriteria%5Bfilter_groups%5D%5B0%5D%5Bfilters%5D%5B0%5D%5Bvalue%5D=INN-L000123-%25"
                + "&searchCriteria%5Bfilter_groups%5D%5B0%5D%5Bfilters%5D%5B0%5D%5Bcondition_type%5D=like"
                + "&searchCriteria%5BpageSize%5D=50&searchCriteria%5BcurrentPage%5D=1";

        MagentoOAuth1Signer.Signed signed = signer.sign("GET",
                URI.create("https://qa.getmore.syncrony.com/rest/V1/coupons/search?" + query));

        assertThat(signed.signature()).isEqualTo("H2Nu9I6ik8SCDIBIH4ataaoPmEmACyd2NyaN10JjGWE=");
        assertThat(signed.baseString())
                .startsWith("GET&https%3A%2F%2Fqa.getmore.syncrony.com%2Frest%2FV1%2Fcoupons%2Fsearch&")
                .contains("INN-L000123-%2525")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("RFC 3986: space %20, '*' %2A, '+' %2B, '%' %25, '~' kept, brackets encoded; decoding keeps '+'")
    void rfc3986() {
        assertThat(Rfc3986.encode("a b")).isEqualTo("a%20b");
        assertThat(Rfc3986.encode("*")).isEqualTo("%2A");
        assertThat(Rfc3986.encode("+")).isEqualTo("%2B");
        assertThat(Rfc3986.encode("%")).isEqualTo("%25");
        assertThat(Rfc3986.encode("~-._")).isEqualTo("~-._");
        assertThat(Rfc3986.encode("[0]")).isEqualTo("%5B0%5D");
        assertThat(Rfc3986.encode("é")).isEqualTo("%C3%A9");
        assertThat(Rfc3986.decode("a+b%20c%5B%5d")).isEqualTo("a+b c[]");
        assertThat(Rfc3986.decode("%C3%A9")).isEqualTo("é");
        assertThatThrownBy(() -> Rfc3986.decode("bad%2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rfc3986.decode("bad%zz")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parameters sort by encoded name, then by encoded value")
    void sortsByNameThenValue() {
        String base = signer.sign("GET", URI.create("https://h.example/p?b=2&a=z&A=1&c=%2A")).baseString();
        String params = Rfc3986.decode(base.substring(base.lastIndexOf('&') + 1));

        List<String> names = new ArrayList<>();
        for (String pair : params.split("&")) {
            names.add(pair.substring(0, pair.indexOf('=')));
        }
        assertThat(names).containsExactly("A", "a", "b", "c", "oauth_consumer_key", "oauth_nonce",
                "oauth_signature_method", "oauth_timestamp", "oauth_token", "oauth_version");
        assertThat(params).contains("c=%2A");
    }

    @Test
    @DisplayName("scheme and host are lower-cased; the path keeps its case")
    void schemeAndHostLowerCased() {
        assertThat(signer.baseUrl(URI.create("HTTPS://QA.GetMore.Syncrony.COM/rest/V1/coupons/generate")))
                .isEqualTo("https://qa.getmore.syncrony.com/rest/V1/coupons/generate");
    }

    @Test
    @DisplayName("the base URL carries no query and no fragment")
    void queryExcludedFromBaseUrl() {
        assertThat(signer.baseUrl(URI.create("https://h.example/rest/V1/orders?x=1#frag")))
                .isEqualTo("https://h.example/rest/V1/orders");
    }

    @Test
    @DisplayName("https on 443: the port is dropped")
    void https443Dropped() {
        assertThat(signer.baseUrl(URI.create("https://h.example:443/p"))).isEqualTo("https://h.example/p");
    }

    @Test
    @DisplayName("http on 80: the port is dropped")
    void http80Dropped() {
        assertThat(signer.baseUrl(URI.create("http://h.example:80/p"))).isEqualTo("http://h.example/p");
    }

    @Test
    @DisplayName("https on 80: the port is kept")
    void https80Kept() {
        assertThat(signer.baseUrl(URI.create("https://h.example:80/p"))).isEqualTo("https://h.example:80/p");
    }

    @Test
    @DisplayName("http on 443: the port is kept")
    void http443Kept() {
        assertThat(signer.baseUrl(URI.create("http://h.example:443/p"))).isEqualTo("http://h.example:443/p");
    }

    @Test
    @DisplayName("any other port is kept")
    void nonDefaultPortKept() {
        assertThat(signer.baseUrl(URI.create("https://h.example:8443/p"))).isEqualTo("https://h.example:8443/p");
    }

    @Test
    @DisplayName("a parameter named twice is refused: Magento keeps only the last, so we would sign what it never sees")
    void duplicateNameRefused() {
        assertThatThrownBy(() -> signer.sign("GET", URI.create("https://h.example/p?a=1&a=2")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> signer.sign("GET", URI.create("https://h.example/p?a=1&%61=2")))
                .as("the same name, once encoded").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a parameter with an empty name is refused")
    void emptyNameRefused() {
        assertThatThrownBy(() -> signer.sign("GET", URI.create("https://h.example/p?=1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty name");
    }

    @Test
    @DisplayName("a pair with no '=' is refused")
    void pairWithoutEqualsRefused() {
        assertThatThrownBy(() -> signer.sign("GET", URI.create("https://h.example/p?flag")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("without '='");
        assertThatThrownBy(() -> signer.sign("GET", URI.create("https://h.example/p?a=1&&b=2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the JSON body is never signed: the signature depends on method, URL, query and credentials only")
    void bodyNeverSigned() {
        // The signer is given no body at all; a POST with any body signs exactly as worked example 1.
        URI generate = URI.create("https://qa.getmore.syncrony.com/rest/V1/coupons/generate");
        assertThat(signer.sign("POST", generate).signature())
                .isEqualTo("j3EDqMXSXCI0+GKWGIyLd140drbngWcEqeNbkPA02Mw=");
        assertThat(signer.sign("POST", generate).baseString()).doesNotContain("couponSpec", "rule_id");
    }

    @Test
    @DisplayName("GETMORE_SIGNATURE_BASE_URL: the request goes to the base URL but is signed as the override")
    void signatureBaseUrlOverride() {
        GetMoreProperties.Live live = live();
        live.setBaseUrl("http://localhost:8099/rest/V1/");
        live.setSignatureBaseUrl("https://qa.getmore.syncrony.com/rest/V1");
        MagentoOAuth1Signer overridden = fixedSigner(live);

        MagentoOAuth1Signer.Signed signed = overridden.sign("POST",
                URI.create("http://localhost:8099/rest/V1/coupons/generate"));

        assertThat(signed.signature()).as("signs as worked example 1").isEqualTo(
                "j3EDqMXSXCI0+GKWGIyLd140drbngWcEqeNbkPA02Mw=");

        live.setSignatureBaseUrl("http://qa.getmore.syncrony.com/rest/V1");
        assertThat(fixedSigner(live).baseUrl(URI.create("http://localhost:8099/rest/V1/coupons/search?x=1")))
                .as("TLS terminated in front of Magento: sign http://").isEqualTo(
                        "http://qa.getmore.syncrony.com/rest/V1/coupons/search");
        assertThatThrownBy(() -> fixedSigner(live).baseUrl(URI.create("http://localhost:8099/other/coupons")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the nonce is 32 characters of [a-z0-9], fresh on every call; each call signs with its own")
    void nonceIs32AndFresh() {
        Set<String> nonces = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String nonce = MagentoOAuth1Signer.secureNonce();
            assertThat(nonce).matches("[a-z0-9]{32}");
            nonces.add(nonce);
        }
        assertThat(nonces).hasSize(200);

        MagentoOAuth1Signer real = new MagentoOAuth1Signer(live(), CLOCK, MagentoOAuth1Signer::secureNonce);
        URI uri = URI.create("https://qa.getmore.syncrony.com/rest/V1/coupons/generate");
        assertThat(real.authorization("POST", uri)).isNotEqualTo(real.authorization("POST", uri));
    }

    @Test
    @DisplayName("a signing prints nothing: the header carries the consumer key and the token")
    void signedPrintsNothing() {
        MagentoOAuth1Signer.Signed signed = signer.sign("POST", URI.create("https://h.example/p"));
        assertThat(signed.toString()).doesNotContain(CONSUMER_KEY, TOKEN, "oauth");
    }
}
