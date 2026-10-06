package zw.co.innbucks.loans.core.disbursements;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only a failure before the connection opened proves nothing was sent. A read timeout and a reset
 * share types with connect-phase failures and must never be mistaken for one: InnBucks books and
 * pays on the call, so "not sent" is what lets a loan be booked again.
 */
class ConnectPhaseTest {

    private static ResourceAccessException wrapped(Exception cause) {
        return new ResourceAccessException("I/O error on POST request: " + cause.getMessage(), (IOException) cause);
    }

    @Test
    @DisplayName("connection refused, unknown host and a CONNECT timeout never reached the server")
    void connectPhaseFailures() {
        assertThat(ConnectPhase.neverConnected(wrapped(new ConnectException("Connection refused")))).isTrue();
        assertThat(ConnectPhase.neverConnected(wrapped(new UnknownHostException("innbucks.example")))).isTrue();
        assertThat(ConnectPhase.neverConnected(wrapped(new SocketTimeoutException("Connect timed out")))).isTrue();
    }

    @Test
    @DisplayName("httpclient5 (the pooled transport): a connect timeout and a timed-out wait for a pooled "
            + "connection never reached the server")
    void pooledTransportPreSendFailures() {
        assertThat(ConnectPhase.neverConnected(wrapped(new org.apache.hc.client5.http.ConnectTimeoutException(
                "Connect to http://innbucks.example:443 failed: Connect timed out")))).isTrue();
        assertThat(ConnectPhase.neverConnected(wrapped(new org.apache.hc.core5.http.ConnectionRequestTimeoutException(
                "Timeout deadline: 2000 MILLISECONDS, actual: 2001 MILLISECONDS")))).isTrue();
        // A connection refused is httpclient5's HttpHostConnectException, a ConnectException.
        assertThat(ConnectPhase.neverConnected(wrapped(new org.apache.hc.client5.http.HttpHostConnectException(
                "Connect to http://innbucks.example:443 failed: Connection refused")))).isTrue();
    }

    @Test
    @DisplayName("httpclient5's after-connect failures stay possibly-sent: no response on a pooled connection")
    void pooledTransportAfterConnectFailures() {
        assertThat(ConnectPhase.neverConnected(wrapped(new org.apache.hc.core5.http.NoHttpResponseException(
                "innbucks.example:443 failed to respond")))).isFalse();
    }

    @Test
    @DisplayName("a READ timeout and a reset may follow a request that was processed")
    void afterConnectFailures() {
        assertThat(ConnectPhase.neverConnected(wrapped(new SocketTimeoutException("Read timed out")))).isFalse();
        assertThat(ConnectPhase.neverConnected(wrapped(new SocketException("Connection reset")))).isFalse();
        assertThat(ConnectPhase.neverConnected(new IllegalStateException("no cause"))).isFalse();
    }
}
