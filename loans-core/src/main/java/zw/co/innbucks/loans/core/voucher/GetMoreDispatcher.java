package zw.co.innbucks.loans.core.voucher;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.config.DispatcherExecutor;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * GetMore work that must not hold up the request that asked for it (D18): a borrower's read of their loan moves its
 * voucher on at GetMore on a thread of its own (an issuance that is due is attempted, a cancelled loan's is cleaned up,
 * the voucher is synced), so a slow GetMore never blocks the SuperApp, and what that read returns is what is already
 * stored (the app sees the change on its next read). A cancelled loan's GetMore coupons are withdrawn here too, once
 * the cancellation commits. Nothing here is a job: work is queued only when someone asks.
 *
 * <p>Each piece of work has a key ({@code refresh:<staffMemberId>}, {@code sync:<voucherId>},
 * {@code cleanup:<issuanceId>}); one already queued or running is not queued again, so a borrower refreshing their
 * screen ten times costs one sync. The thread is a
 * {@link DispatcherExecutor} like the voucher deliveries': one thread, carrying the trace of the request that queued
 * the work, with a bounded queue of {@link #QUEUE_CAPACITY}; work that does not fit is counted
 * ({@code loans.executor.rejected{executor="getmore"}}) and dropped, and the next read asks again. Not a Spring
 * {@code Executor} bean.</p>
 */
@Slf4j
@Component
public class GetMoreDispatcher {

    static final String EXECUTOR = "getmore";
    /** Each entry is a key; the bound is there for a GetMore outage, not to shed ordinary load. */
    static final int QUEUE_CAPACITY = 1_000;
    /** A borrower has one open loan at a time; a few recent vouchers are refreshed at most. */
    static final int VOUCHERS_PER_REFRESH = 5;

    private final GetMoreVoucherSync sync;
    private final StaffLoanVoucherIssuer issuer;
    private final VoucherRepository voucherRepository;
    private final GetMoreProperties properties;
    private final MarketTimeZone marketTimeZone;
    private final Executor executor;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    @Autowired
    public GetMoreDispatcher(GetMoreVoucherSync sync, StaffLoanVoucherIssuer issuer,
                             VoucherRepository voucherRepository, GetMoreProperties properties,
                             MarketTimeZone marketTimeZone, ObjectProvider<TaskDecorator> taskDecorator,
                             ObjectProvider<MeterRegistry> meterRegistry) {
        this(sync, issuer, voucherRepository, properties, marketTimeZone, new DispatcherExecutor(EXECUTOR,
                QUEUE_CAPACITY, taskDecorator.getIfUnique(), meterRegistry.getIfAvailable()));
    }

    /** With the executor work runs on; a test passes one that runs it at once. */
    public GetMoreDispatcher(GetMoreVoucherSync sync, StaffLoanVoucherIssuer issuer,
                             VoucherRepository voucherRepository, GetMoreProperties properties,
                             MarketTimeZone marketTimeZone, Executor executor) {
        this.sync = sync;
        this.issuer = issuer;
        this.voucherRepository = voucherRepository;
        this.properties = properties;
        this.marketTimeZone = marketTimeZone;
        this.executor = executor;
    }

    /**
     * Brings a borrower's GetMore vouchers up to date in the background: first their loans' voucher issuances (one due
     * is attempted, a cancelled loan's cleaned up: {@link StaffLoanVoucherIssuer#refreshFor}), then each voucher in
     * this host's mode, not cancelled, still within the sync window after its expiry, and not synced in the last
     * {@code loans.getmore.sync.min-interval}. Never blocks and never throws; nothing is queued while GetMore is OFF.
     */
    public void refresh(Long staffMemberId) {
        if (properties.getMode() == GetMoreMode.OFF || staffMemberId == null) {
            return;
        }
        submit("refresh:" + staffMemberId, () -> refreshNow(staffMemberId));
    }

    /** Syncs one voucher in the background, unless a sync of it is already queued. Never blocks and never throws. */
    public void sync(Long voucherId, GetMoreSyncTrigger trigger) {
        submit("sync:" + voucherId, () -> sync.sync(voucherId, trigger));
    }

    /**
     * Withdraws what a cancelled loan's voucher issuance left at GetMore, in the background
     * ({@link StaffLoanVoucherIssuer#cleanup}): queued once the cancellation commits. Never blocks and never throws; a
     * cleanup that cannot finish is tried again by the borrower's next read or by Credit.
     */
    public void cleanup(Long issuanceId) {
        submit("cleanup:" + issuanceId, () -> issuer.cleanup(issuanceId));
    }

    /**
     * Queues {@code work} under {@code key} unless work under that key is already queued or running.
     *
     * @return whether it was queued
     */
    boolean submit(String key, Runnable work) {
        if (!queued.add(key)) {
            log.debug("GetMore work {} is already queued; not queued again", key);
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException ex) {
                    log.error("GetMore work {} failed; it runs again when next asked", key, ex);
                } finally {
                    queued.remove(key);
                }
            });
            return true;
        } catch (RejectedExecutionException refused) {
            queued.remove(key);
            log.error("GetMore work {} could not be queued; it runs again when next asked", key, refused);
            return false;
        }
    }

    private void refreshNow(Long staffMemberId) {
        try {
            issuer.refreshFor(staffMemberId);
        } catch (RuntimeException ex) {
            log.error("GetMore voucher issuances of staff member {} could not be moved on; the next read asks again",
                    staffMemberId, ex);
        }
        GetMoreMode mode = properties.getMode();
        LocalDateTime now = marketTimeZone.nowUtc();
        List<Long> due = voucherRepository.findGetMoreIdsToRefresh(staffMemberId, mode,
                now.minus(properties.getSync().getAfterExpiry()), now.minus(properties.getSync().getMinInterval()),
                VOUCHERS_PER_REFRESH);
        for (Long voucherId : due) {
            try {
                sync.sync(voucherId, GetMoreSyncTrigger.BORROWER_READ);
            } catch (RuntimeException ex) {
                log.error("GetMore refresh of voucher {} for staff member {} failed; the next read asks again",
                        voucherId, staffMemberId, ex);
            }
        }
    }

    /** Whether work under {@code key} is queued or running. */
    boolean isQueued(String key) {
        return queued.contains(key);
    }

    @PreDestroy
    void stop() {
        if (executor instanceof DispatcherExecutor own) {
            own.shutdownNow();
        } else if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
