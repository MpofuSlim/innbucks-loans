package zw.co.innbucks.loans.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.http.HttpRequest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@link Propagator} that writes trace headers ({@code traceparent},
 * {@code tracestate}, {@code baggage}) ONLY onto a request addressed to a fleet
 * service by its discovery name ({@code http://user-service/...}), and never onto
 * anything else.
 *
 * <p>Loans calls NO fleet service today; every outbound call is a partner's:
 * InnBucks (loan booking, deposits, its login), Ndasenda (lodgement and
 * deductions), the InnBucks notification API and the WhatsApp gateway (SES is
 * SMTP and carries no HTTP headers). None of them may receive
 * {@code traceparent}: partner WAFs have refused requests carrying headers they
 * did not expect, and a trace id is our internal detail besides. Every one of
 * those clients is built without an observation registry today, so none would
 * send one — but that is a property of how each client happens to be
 * constructed, which the next refactor can change. This makes it a property of
 * the DESTINATION instead: a request to a host with a dot in it (every partner)
 * or to {@code localhost} gets nothing, however its client was built. It fails
 * closed — a carrier that is not an HTTP request is not written to at all.
 *
 * <p>Should loans ever call a fleet service, it does so by the Service name
 * ({@code http://user-service:8081}), which is admitted. Extraction (the
 * gateway's {@code traceparent} on incoming requests) is untouched.
 */
public final class FleetOnlyPropagator implements Propagator {

    /** A discovery name: what the static discovery map is keyed by. Never a dotted host. */
    private static final Pattern FLEET_HOST = Pattern.compile("[a-z][a-z0-9-]*-service");

    private final Propagator delegate;

    public FleetOnlyPropagator(Propagator delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<String> fields() {
        return delegate.fields();
    }

    @Override
    public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
        if (isFleetDestination(carrier)) {
            delegate.inject(context, carrier, setter);
        }
    }

    @Override
    public <C> Span.Builder extract(C carrier, Getter<C> getter) {
        return delegate.extract(carrier, getter);
    }

    static boolean isFleetDestination(Object carrier) {
        if (!(carrier instanceof HttpRequest request)) {
            return false;
        }
        String host = request.getURI().getHost();
        return host != null && FLEET_HOST.matcher(host).matches();
    }
}
