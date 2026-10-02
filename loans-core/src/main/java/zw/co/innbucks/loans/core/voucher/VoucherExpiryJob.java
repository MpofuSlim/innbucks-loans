package zw.co.innbucks.loans.core.voucher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.staff.StaffLoanJobs;

/**
 * Marks vouchers that have lapsed with something left EXPIRED (FR-SGL-039), and sends any voucher left PENDING by a
 * stop. Only where the Staff Grocery Loan jobs run ({@link StaffLoanJobs}). Nothing depends on it running on time: a
 * lapsed voucher is refused at the till and reads EXPIRED everywhere from the moment it lapses; this only brings the
 * stored status up to date. Re-running it changes nothing more.
 */
@Slf4j
@Component
@StaffLoanJobs
@RequiredArgsConstructor
public class VoucherExpiryJob {

    private final VoucherRepository voucherRepository;
    private final VoucherDeliveryDispatcher dispatcher;
    private final MarketTimeZone marketTimeZone;

    @Transactional
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "${loans.vouchers.sweep-interval:PT15M}")
    public void sweep() {
        int expired = voucherRepository.expireLapsed(VoucherService.open(), VoucherStatus.EXPIRED,
                marketTimeZone.nowUtc());
        if (expired > 0) {
            log.info("{} lapsed vouchers marked EXPIRED", expired);
        }
    }

    /** Only hands the sweep to the dispatcher's thread, so it holds the shared scheduler thread for no time at all. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${loans.vouchers.sweep-interval:PT15M}")
    public void sendPending() {
        dispatcher.requestSweep();
    }
}
