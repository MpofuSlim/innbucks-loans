package zw.co.innbucks.loans.core.getmore.simulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.CouponRequest;
import zw.co.innbucks.loans.core.getmore.Deadline;
import zw.co.innbucks.loans.core.getmore.GenerateGuard;
import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponSpec;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponUpdate;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMorePage;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException.Reason;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The simulated store's facade leaves every failure as one of the port's three kinds, as the live client does, so the
 * code above the port runs the same path in SIMULATED as in LIVE: a backend transaction that never began applied
 * nothing ({@code NOT_REACHED}: a generate's count is given back), any other failure of a write may have committed
 * (outcome unknown), and a read failed ({@code READ_FAILED}). Only the class name of the failure is kept.
 */
class SimulatedGetMoreStoreTest {

    private static final String PREFIX = "SIM-K7Q2M3XW-";
    /** What Postgres puts in a unique violation's message: the value, a code here. */
    private static final String DUPLICATE_DETAIL = "duplicate key value violates unique constraint"
            + " \"uq_getmore_sim_coupons_code\"; Detail: Key (upper(code))=(SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C)";

    private final SimulatedStoreBackend backend = mock(SimulatedStoreBackend.class);
    private final SimulatedGetMoreStore store = new SimulatedGetMoreStore(backend, new SimulatorFaults());

    private static void unavailable(Throwable thrown, Operation operation, Reason reason) {
        assertThat(thrown).isInstanceOfSatisfying(GetMoreUnavailableException.class, e -> {
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e.operation()).isEqualTo(operation);
        });
    }

    private static Throwable failure(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected the call to fail");
    }

    @Test
    @DisplayName("a backend transaction that never began (no pooled connection in time): NOT_REACHED, on a write too")
    void transactionNeverBegunIsNotReached() {
        when(backend.generate(any())).thenThrow(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));
        when(backend.searchCoupons(any())).thenThrow(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

        Throwable generate = failure(() -> store.generateCoupons(GetMoreCouponSpec.single(43, PREFIX, 16)));
        Throwable search = failure(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 50)));

        unavailable(generate, Operation.GENERATE, Reason.NOT_REACHED);
        unavailable(search, Operation.SEARCH_COUPONS, Reason.NOT_REACHED);
        assertThat(generate).hasMessageContaining("CannotCreateTransactionException").hasMessageNotContaining("JPA");
    }

    @Test
    @DisplayName("any other failure of a write (a code race, a lost connection): it may have committed, outcome"
            + " unknown, and the message keeps the class name only, never the SQL's values")
    void writeFailingOtherwiseIsOutcomeUnknown() {
        when(backend.generate(any())).thenThrow(new DataIntegrityViolationException(DUPLICATE_DETAIL));
        when(backend.updateCoupon(anyLong(), any())).thenThrow(new QueryTimeoutException("timeout"));
        when(backend.deleteCoupons(anyList(), anyBoolean())).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(failure(() -> store.generateCoupons(GetMoreCouponSpec.single(43, PREFIX, 16))))
                .isInstanceOf(GetMoreOutcomeUnknownException.class)
                .hasMessageContaining("DataIntegrityViolationException")
                .hasMessageNotContaining("7KQ2M9XWD4RT8B1C");
        assertThat(failure(() -> store.updateCoupon(1201, new GetMoreCouponUpdate(43, PREFIX + "7KQ2M9XWD4RT8B1C", 1,
                null)))).isInstanceOf(GetMoreOutcomeUnknownException.class);
        assertThat(failure(() -> store.deleteCoupons(List.of(PREFIX + "7KQ2M9XWD4RT8B1C"), true)))
                .isInstanceOf(GetMoreOutcomeUnknownException.class);
    }

    @Test
    @DisplayName("any other failure of a read: READ_FAILED, to be asked again later")
    void readFailingIsReadFailed() {
        when(backend.salesRule(43)).thenThrow(new QueryTimeoutException("timeout"));

        unavailable(failure(() -> store.salesRule(43)), Operation.SALES_RULE, Reason.READ_FAILED);
    }

    @Test
    @DisplayName("a caller's argument refused (IllegalArgumentException) passes through unchanged")
    void argumentRefusalPassesThrough() {
        when(backend.searchCoupons(any())).thenThrow(new IllegalArgumentException("page must be at least 1"));

        assertThatThrownBy(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(PREFIX, 1, 50)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("page must be at least 1");
    }

    @Test
    @DisplayName("find-or-create over a generate whose transaction never began: Unavailable, and the count given back,"
            + " exactly as live's not-reached")
    void findOrCreateGivesTheCountBackWhenNothingBegan() {
        when(backend.salesRule(43)).thenReturn(new GetMoreSalesRule(43, "InnBucks Staff Grocery (SIMULATED)", true,
                null, null, "cart_fixed", BigDecimal.ZERO, "AUTO", true, 0, 0, true, false));
        when(backend.searchCoupons(any())).thenReturn(new GetMorePage<>(List.of(), 0, 1, 50));
        when(backend.generate(any())).thenThrow(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));
        Clock clock = Clock.systemUTC();
        GetMoreCoupons coupons = new GetMoreCoupons(store, new NoBinding(), new GetMoreProperties(),
                new MarketTimeZone("ZW", clock));
        CountingGuard guard = new CountingGuard();

        CouponRequest request = new CouponRequest(PREFIX, 43, new BigDecimal("150.00"), "USD", 16,
                LocalDate.now(clock).plusDays(30), LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).minusMinutes(4),
                guard);

        assertThatThrownBy(() -> coupons.findOrCreate(request, Deadline.none()))
                .isInstanceOfSatisfying(GetMoreUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_REACHED));
        assertThat(guard.counted).isEqualTo(1);
        assertThat(guard.givenBack).isEqualTo(1);
    }

    @Test
    @DisplayName("the allowance bind, which reaches the backend directly: a transaction that never began is"
            + " NOT_REACHED and any other failure outcome unknown, so find-or-create ends in the port's kinds, as live")
    void bindFailuresAreClassifiedLikeTheStore() {
        GetMoreCoupon onPrefix = new GetMoreCoupon(1201, 43, PREFIX + "7KQ2M9XWD4RT8B1C", 0, 0, 0, null, false,
                LocalDateTime.now(ZoneOffset.UTC), 1);
        when(backend.salesRule(43)).thenReturn(rule());
        when(backend.searchCoupons(any())).thenReturn(new GetMorePage<>(List.of(onPrefix), 1, 1, 50));
        doThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"))
                .doThrow(new QueryTimeoutException("timeout"))
                .when(backend).bindAllowance(anyLong(), any(), any());
        GetMoreCoupons coupons = new GetMoreCoupons(store, binding(), new GetMoreProperties(),
                new MarketTimeZone("ZW", Clock.systemUTC()));
        CouponRequest request = request(new CountingGuard());

        assertThatThrownBy(() -> coupons.findOrCreate(request, Deadline.none()))
                .isInstanceOfSatisfying(GetMoreUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.NOT_REACHED);
                    assertThat(e.operation()).isEqualTo(Operation.UPDATE_COUPON);
                    assertThat(e).hasMessageNotContaining("JPA");
                });
        assertThatThrownBy(() -> coupons.findOrCreate(request, Deadline.none()))
                .isInstanceOf(GetMoreOutcomeUnknownException.class)
                .hasMessageContaining("QueryTimeoutException");
    }

    @Test
    @DisplayName("the allowance precheck refuses a face value the till cannot sell or the store cannot hold (a fraction"
            + " of a cent, past NUMERIC(12,4)) before anything is minted; the bind passes a refusal through unchanged")
    void precheckRefusesWhatTheStoreCannotHold() {
        SimulatedAmountBinding binding = binding();

        LocalDate lastDay = LocalDate.now(ZoneOffset.UTC).plusDays(30);
        for (String face : List.of("150.005", "0.001", "100000000.00")) {
            assertThatThrownBy(() -> binding.precheck(rule(), new BigDecimal(face), "USD", lastDay))
                    .as(face).isInstanceOfSatisfying(GetMoreRejectedException.class,
                            e -> assertThat(e.reason()).isEqualTo(GetMoreRejectedException.Reason.VALUE_UNSUPPORTED));
        }
        binding.precheck(rule(), new BigDecimal("99999999.99"), "USD", lastDay);
        binding.precheck(rule(), new BigDecimal("150.0000"), "USD", lastDay);

        GetMoreRejectedException mismatch = new GetMoreRejectedException(null,
                GetMoreRejectedException.Reason.VALUE_UNSUPPORTED, "already worth USD 100");
        doThrow(mismatch).when(backend).bindAllowance(anyLong(), any(), any());
        GetMoreCoupon coupon = new GetMoreCoupon(1201, 43, PREFIX + "7KQ2M9XWD4RT8B1C", 0, 0, 0, null, false, null,
                1);
        assertThatThrownBy(() -> binding.bind(coupon, new BigDecimal("150.00"), "USD")).isSameAs(mismatch);
    }

    private SimulatedAmountBinding binding() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SimulatedStoreBackend> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(backend);
        return new SimulatedAmountBinding(provider, new GetMoreProperties().getSimulator());
    }

    private static GetMoreSalesRule rule() {
        return new GetMoreSalesRule(43, "InnBucks Staff Grocery (SIMULATED)", true, null, null, "cart_fixed",
                BigDecimal.ZERO, "AUTO", true, 0, 0, true, false);
    }

    private static CouponRequest request(GenerateGuard guard) {
        return new CouponRequest(PREFIX, 43, new BigDecimal("150.00"), "USD", 16,
                LocalDate.now(ZoneOffset.UTC).plusDays(30), LocalDateTime.now(ZoneOffset.UTC).minusMinutes(4), guard);
    }

    private static final class CountingGuard implements GenerateGuard {
        int counted;
        int givenBack;

        @Override
        public void beforeGenerate() {
            counted++;
        }

        @Override
        public void notProcessed() {
            givenBack++;
        }
    }

    private static final class NoBinding implements GetMoreAmountBinding {
        @Override
        public void precheck(GetMoreSalesRule rule, BigDecimal faceValue, String currency, LocalDate voucherLastDay) {
        }

        @Override
        public void bind(GetMoreCoupon coupon, BigDecimal faceValue, String currency) {
        }

        @Override
        public boolean partialSpending() {
            return true;
        }

        @Override
        public String name() {
            return "NONE";
        }
    }
}
