package zw.co.innbucks.loans.core.staff.loan;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the Staff Grocery Loan's cash payout reports. {@code loans.staff_loan_payout.outcomes{outcome}} counts what
 * became of each send, inquiry and operator decision; every outcome is registered at 0, so the first one shows as an
 * increase rather than as a series appearing. Worth an alert: any increase in {@code in_doubt}, {@code not_found},
 * {@code declined}, {@code abandoned}, {@code not_recorded}, {@code echo_mismatch} or {@code unknown_bank_status}, any
 * {@code paid_after_cancel} (a borrower may have been paid for a cancelled loan), and a sustained {@code not_sent} or
 * {@code inquiry_unanswered}.
 *
 * <p>{@code loans.staff_loan_payout.pending{status}} and {@code loans.staff_loan_payout.oldest_seconds{status}} show
 * the cash loans awaiting disbursement in each payout status that is still open (READY, SENDING, BOOKED, IN_DOUBT,
 * REFUSED): how many, and how long the oldest has waited. The status job refreshes them on every run; until it first
 * runs, and in a process where it is off, they read 0.
 */
@Component
public class StaffLoanPayoutMetrics {

    public static final String OUTCOMES = "loans.staff_loan_payout.outcomes";
    public static final String PENDING = "loans.staff_loan_payout.pending";
    public static final String OLDEST_SECONDS = "loans.staff_loan_payout.oldest_seconds";

    /** Each thing counted, tagged by its lower-case name. */
    public enum Event {
        /** The bank took a payout (2xx). */
        BOOKED,
        /** The bank refused one (its own 4xx): nothing was booked. */
        REFUSED,
        /** Its answer did not say whether it was booked. */
        IN_DOUBT,
        /** It provably never reached the bank, or the bank did not process it; it is READY again. */
        NOT_SENT,
        /** A guard refused to send it; it stays READY. */
        HELD,
        /** What the bank answered could not be saved, or the loan could not be read to ask about it. */
        NOT_RECORDED,
        /** The bank's inquiry says the money is in the borrower's account. */
        PAID,
        /** The bank's inquiry says it holds no loan under the reference (counted the first time). */
        NOT_FOUND,
        /** A claim never settled (the process died mid-call), held IN_DOUBT. */
        ABANDONED,
        /** The bank says it paid, but its answer names another reference, amount or account. */
        ECHO_MISMATCH,
        /** The bank paid it, and its answer charges interest the loan does not. */
        INTEREST_CHARGED,
        /** The bank holds it in a status this service does not know. */
        UNKNOWN_BANK_STATUS,
        /** The bank holds it in a status that says it declined it, and will not pay it (counted the first time). */
        DECLINED,
        /** The bank holds a payout a person confirmed was never booked. */
        PAID_AFTER_CANCEL,
        /** The bank's inquiry did not answer, or said nothing that could be concluded from. */
        INQUIRY_UNANSWERED,
        /** The inquiry itself broke. */
        INQUIRY_ERROR,
        /** A person recorded, having asked the bank, that it was paid. */
        CONFIRMED_PAID,
        /** A person recorded, having asked the bank, that it was never booked. */
        CONFIRMED_NOT_BOOKED;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The payout statuses of a cash loan still awaiting disbursement: what the gauges show. */
    static final List<StaffLoanPayoutStatus> GAUGED = List.of(StaffLoanPayoutStatus.READY,
            StaffLoanPayoutStatus.SENDING, StaffLoanPayoutStatus.BOOKED, StaffLoanPayoutStatus.IN_DOUBT,
            StaffLoanPayoutStatus.REFUSED);

    private final Map<Event, Counter> counters = new EnumMap<>(Event.class);
    private final Map<StaffLoanPayoutStatus, AtomicLong> pending = new EnumMap<>(StaffLoanPayoutStatus.class);
    private final Map<StaffLoanPayoutStatus, AtomicLong> oldestSeconds = new EnumMap<>(StaffLoanPayoutStatus.class);

    @Autowired
    public StaffLoanPayoutMetrics(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    public StaffLoanPayoutMetrics(MeterRegistry registry) {
        for (Event event : Event.values()) {
            counters.put(event, Counter.builder(OUTCOMES)
                    .description("What became of the Staff Grocery Loan cash payouts sent, asked about and decided")
                    .tag("outcome", event.tag())
                    .register(registry));
        }
        for (StaffLoanPayoutStatus status : GAUGED) {
            AtomicLong count = new AtomicLong();
            AtomicLong age = new AtomicLong();
            pending.put(status, count);
            oldestSeconds.put(status, age);
            Gauge.builder(PENDING, count, AtomicLong::get)
                    .description("Cash Staff Grocery Loans awaiting disbursement, by payout status")
                    .tag("status", status.name())
                    .register(registry);
            Gauge.builder(OLDEST_SECONDS, age, AtomicLong::get)
                    .description("How long the oldest cash Staff Grocery Loan has waited in this payout status")
                    .baseUnit("seconds")
                    .tag("status", status.name())
                    .register(registry);
        }
    }

    public void count(Event event) {
        counters.get(event).increment();
    }

    /** How many times {@code event} has been counted. */
    public double counted(Event event) {
        return counters.get(event).count();
    }

    /** Sets the gauges from the backlog read at {@code now}; a status with no row in it reads 0. */
    public void backlog(Collection<StaffLoanPayoutBacklog> backlog, LocalDateTime now) {
        Map<StaffLoanPayoutStatus, StaffLoanPayoutBacklog> byStatus = new EnumMap<>(StaffLoanPayoutStatus.class);
        for (StaffLoanPayoutBacklog row : backlog) {
            if (row.status() != null) {
                byStatus.put(row.status(), row);
            }
        }
        for (StaffLoanPayoutStatus status : GAUGED) {
            StaffLoanPayoutBacklog row = byStatus.get(status);
            pending.get(status).set(row == null || row.loans() == null ? 0 : row.loans());
            oldestSeconds.get(status).set(row == null ? 0 : row.oldestAge(now).toSeconds());
        }
    }

    long pending(StaffLoanPayoutStatus status) {
        return pending.get(status).get();
    }

    long oldestSeconds(StaffLoanPayoutStatus status) {
        return oldestSeconds.get(status).get();
    }
}
