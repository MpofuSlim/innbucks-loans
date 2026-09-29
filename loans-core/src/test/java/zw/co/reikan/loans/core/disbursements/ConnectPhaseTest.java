package zw.co.reikan.loans.core.disbursements;

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
    @DisplayName("a READ timeout and a reset may follow a request that was processed")
    void afterConnectFailures() {
        assertThat(ConnectPhase.neverConnected(wrapped(new SocketTimeoutException("Read timed out")))).isFalse();
        assertThat(ConnectPhase.neverConnected(wrapped(new SocketException("Connection reset")))).isFalse();
        assertThat(ConnectPhase.neverConnected(new IllegalStateException("no cause"))).isFalse();
    }
}
