package zw.co.innbucks.loans.security;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorApiKey;
import zw.co.innbucks.loans.web.RequestBodyTooLargeException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key is the first thing the simulator's chain looks at, and nothing of the body is read before it: a wrong or
 * missing key is 401 with the request's stream untouched; a declared body over 8 KiB is 413, unread; a body with no
 * declared length is cut off past the same limit; and the right key leaves the caller holding the simulator role and
 * nothing else.
 */
class SimulatorApiKeyFilterTest {

    private static final String KEY = "staging-getmore-simulator-key-0123456789abcdef";

    private final SimulatorApiKeyFilter filter = new SimulatorApiKeyFilter(new SimulatorApiKey(KEY));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A request whose body fails the test if anything opens it. */
    private static MockHttpServletRequest untouchable(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/lending/v1/getmore-simulator/sales") {
            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("the body was read");
            }

            @Override
            public BufferedReader getReader() {
                throw new AssertionError("the body was read");
            }
        };
        request.setContentType("application/json");
        request.setContent("{ not json".getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            request.addHeader("X-Api-Key", key);
        }
        return request;
    }

    @Test
    @DisplayName("a wrong or missing key: 401 in the envelope, the body never opened, the chain not continued")
    void wrongKey() throws Exception {
        for (String key : new String[]{null, "", KEY + "x", KEY.substring(1), KEY.toUpperCase()}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(untouchable(key), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).startsWith("application/json");
            assertThat(response.getContentAsString())
                    .isEqualTo("{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or missing api key\"}");
            assertThat(response.getHeader("WWW-Authenticate")).isNull();
            assertThat(chain.getRequest()).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    @DisplayName("a blank configured key accepts nothing, not even a blank header")
    void blankConfiguredKey() throws Exception {
        SimulatorApiKeyFilter unconfigured = new SimulatorApiKeyFilter(new SimulatorApiKey("  "));
        for (String key : new String[]{null, "", "  "}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            unconfigured.doFilter(untouchable(key), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("the right key with a declared body over 8 KiB: 413, unread")
    void declaredTooLarge() throws Exception {
        MockHttpServletRequest declared = new MockHttpServletRequest("POST", "/lending/v1/getmore-simulator/sales") {
            @Override
            public long getContentLengthLong() {
                return SimulatorApiKeyFilter.MAX_BODY_BYTES + 1L;
            }

            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("the body was read");
            }
        };
        declared.addHeader("X-Api-Key", KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(declared, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"")
                .contains("8192 bytes");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("the right key: the chain goes on as the simulator, holding ROLE_GETMORE_SIMULATOR and nothing else")
    void rightKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/lending/v1/getmore-simulator/sales");
        request.addHeader("X-Api-Key", KEY);
        request.setContent("{\"amount\": 45.50}".getBytes(StandardCharsets.UTF_8));
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ServletRequest req, ServletResponse res) -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
            body.set(new String(((HttpServletRequest) req).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get().getName()).isEqualTo("getmore-simulator");
        assertThat(seen.get().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_GETMORE_SIMULATOR");
        assertThat(body.get()).isEqualTo("{\"amount\": 45.50}");
    }

    @Test
    @DisplayName("a body with no declared length is cut off past 8 KiB, whether read by the stream or the reader")
    void undeclaredTooLarge() throws Exception {
        byte[] big = new byte[SimulatorApiKeyFilter.MAX_BODY_BYTES + 1];
        Arrays.fill(big, (byte) 'x');
        for (boolean reader : new boolean[]{false, true}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST",
                    "/lending/v1/getmore-simulator/sales") {
                @Override
                public long getContentLengthLong() {
                    return -1;
                }
            };
            request.addHeader("X-Api-Key", KEY);
            request.setContent(big);

            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                HttpServletRequest capped = (HttpServletRequest) req;
                if (reader) {
                    assertThatThrownBy(() -> drain(capped.getReader()))
                            .isInstanceOf(RequestBodyTooLargeException.class)
                            .hasMessage("The request body must be at most 8192 bytes");
                } else {
                    assertThatThrownBy(() -> capped.getInputStream().readAllBytes())
                            .isInstanceOf(RequestBodyTooLargeException.class);
                }
            });
        }
    }

    @Test
    @DisplayName("a body of exactly 8 KiB with no declared length is read whole")
    void undeclaredAtTheLimit() throws Exception {
        byte[] exact = new byte[SimulatorApiKeyFilter.MAX_BODY_BYTES];
        Arrays.fill(exact, (byte) 'x');
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/lending/v1/getmore-simulator/sales") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.addHeader("X-Api-Key", KEY);
        request.setContent(exact);
        AtomicReference<Integer> length = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                length.set(((HttpServletRequest) req).getInputStream().readAllBytes().length));

        assertThat(length.get()).isEqualTo(SimulatorApiKeyFilter.MAX_BODY_BYTES);
    }

    @Test
    @DisplayName("the audit names a key by the first 8 hex characters of its SHA-256, and an absent one as none")
    void fingerprint() {
        assertThat(SimulatorApiKey.fingerprint(KEY)).hasSize(8).matches("[0-9a-f]{8}")
                .isNotEqualTo(SimulatorApiKey.fingerprint(KEY + "x"));
        assertThat(SimulatorApiKey.fingerprint(null)).isEqualTo("none");
        assertThat(SimulatorApiKey.fingerprint("")).isEqualTo("none");
        assertThat(new SimulatorApiKey(KEY).toString()).doesNotContain(KEY);
    }

    private static void drain(BufferedReader reader) throws IOException {
        char[] buffer = new char[1024];
        while (reader.read(buffer) >= 0) {
            // reading to the end
        }
    }
}
