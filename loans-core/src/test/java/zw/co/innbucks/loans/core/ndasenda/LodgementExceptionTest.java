package zw.co.innbucks.loans.core.ndasenda;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only a failure to CONNECT proves a lodgement never left; every other transport failure may follow
 * one Ndasenda received. The cases a real socket cannot produce on demand (a connect timeout, an
 * unresolvable host) are pinned here.
 */
class LodgementExceptionTest {

    /** As RestTemplate wraps it. */
    private static LodgementException.Kind kindOf(IOException cause) {
        return LodgementException.classify(new ResourceAccessException("I/O error on POST request", cause)).getKind();
    }

    @Test
    @DisplayName("connect-phase failures never left: connect timeout, refused, unknown host, no route")
    void connectPhaseFailuresNeverLeft() {
        assertThat(kindOf(new SocketTimeoutException("Connect timed out"))).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertThat(kindOf(new SocketTimeoutException("connect timed out"))).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertThat(kindOf(new ConnectException("Connection refused"))).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertThat(kindOf(new UnknownHostException("deductions.ndasenda.co.zw"))).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertThat(kindOf(new NoRouteToHostException("No route to host"))).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
    }

    @Test
    @DisplayName("httpclient5 (the pooled transport): a connect timeout and a timed-out wait for a pooled connection never left")
    void pooledTransportPreSendFailuresNeverLeft() {
        assertThat(kindOf(new org.apache.hc.client5.http.ConnectTimeoutException(
                "Connect to https://deductions.ndasenda.co.zw:443 failed: Connect timed out")))
                .isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertThat(kindOf(new org.apache.hc.core5.http.ConnectionRequestTimeoutException(
                "Timeout deadline: 500 MILLISECONDS, actual: 501 MILLISECONDS")))
                .isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertThat(kindOf(new org.apache.hc.core5.http.NoHttpResponseException(
                "deductions.ndasenda.co.zw:443 failed to respond")))
                .isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
    }

    @Test
    @DisplayName("a read timeout, or a timeout that does not say it was the connect, may follow a delivered request")
    void readTimeoutIsUnknown() {
        assertThat(kindOf(new SocketTimeoutException("Read timed out"))).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertThat(kindOf(new SocketTimeoutException(null))).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
    }

    @Test
    @DisplayName("4xx: 401 and 429 processed nothing; 408 and 409 are unknown; the rest are refusals")
    void clientErrors() {
        assertThat(classify(HttpStatus.UNAUTHORIZED)).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertThat(classify(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertThat(classify(HttpStatus.REQUEST_TIMEOUT)).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertThat(classify(HttpStatus.CONFLICT)).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertThat(classify(HttpStatus.BAD_REQUEST)).isEqualTo(LodgementException.Kind.REFUSED);
        assertThat(classify(HttpStatus.FORBIDDEN)).isEqualTo(LodgementException.Kind.REFUSED);
        assertThat(classify(HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(LodgementException.Kind.REFUSED);
    }

    @Test
    @DisplayName("an unclassified exception is unknown, and its message is not trusted into the reason")
    void unclassifiedIsUnknownAndMessageNotCopied() {
        LodgementException ex = LodgementException.classify(new IllegalStateException("parsed 631234567A63"));

        assertThat(ex.getKind()).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertThat(ex.getMessage()).isEqualTo("IllegalStateException").doesNotContain("631234567A63");
    }

    private static LodgementException.Kind classify(HttpStatus status) {
        return LodgementException.classify(HttpClientErrorException.create(status, status.getReasonPhrase(),
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)).getKind();
    }
}
