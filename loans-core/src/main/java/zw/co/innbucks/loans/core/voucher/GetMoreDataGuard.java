package zw.co.innbucks.loans.core.voucher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.simulator.SimCouponRepository;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.merchant.VoucherStore;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceRepository;

import java.util.Optional;

/**
 * GetMore's boot checks against what the database already holds, in the style of {@code GetMoreModeReport}: the
 * constructor refuses to start ({@link IllegalStateException}) where the mode and the data disagree in a way that could
 * do harm, and warns where they merely look wrong. It reads through the repositories, so it runs after Flyway, and it
 * is a constructor dependency of the {@code GetMoreStore} bean and of the simulator's seeder, so it runs before either
 * exists and before the web connector opens.
 *
 * <ul>
 *   <li>LIVE or SIMULATED without both voucher code keys: refused, since a GetMore code could not be stored.</li>
 *   <li>SIMULATED on a database holding a LIVE GetMore voucher, or a LIVE voucher issuance: refused. A database that
 *       holds real coupons is a production one, whatever its env file says, and so is one that has issued for GetMore's
 *       real store at all: LIVE with {@code GETMORE_LIVE_AMOUNT_MODEL=NONE} parks every accepted loan's issuance with
 *       no voucher, and an issuance can keep real coupons' codes sealed (a lost answer, live orphans).</li>
 *   <li>LIVE or OFF with rows in the simulated store: a WARN. LIVE with simulated vouchers: a WARN with how many
 *       (their syncs are skipped, and the reports leave them out).</li>
 *   <li>Not OFF while the Staff Grocery Loan's merchant is not a GetMore store: a WARN, since every loan accepted will
 *       wait for a voucher no one issues. {@code GET /lending/v1/getmore} says the same while it holds.</li>
 * </ul>
 * Every message names the variables or the record to change, never a value.
 */
@Slf4j
@Component
public class GetMoreDataGuard {

    private final GetMoreMode mode;

    public GetMoreDataGuard(GetMoreProperties properties, VoucherCodeVault vault, VoucherRepository voucherRepository,
                            StaffLoanVoucherIssuanceRepository issuanceRepository,
                            SimCouponRepository simCouponRepository, MerchantRepository merchantRepository) {
        this.mode = properties.getMode();
        if (mode != GetMoreMode.OFF && !vault.isConfigured()) {
            throw new IllegalStateException("GETMORE_MODE is " + mode + " but VOUCHER_CODE_HMAC_KEY and"
                    + " VOUCHER_CODE_ENCRYPTION_KEY are required to store GetMore codes: set both, or set"
                    + " GETMORE_MODE=OFF");
        }
        if (mode == GetMoreMode.SIMULATED && (voucherRepository.existsByStoreMode(GetMoreMode.LIVE)
                || issuanceRepository.existsByStoreMode(GetMoreMode.LIVE))) {
            throw new IllegalStateException("GETMORE_MODE is SIMULATED but this database holds real GetMore vouchers"
                    + " or voucher issuances (store mode LIVE, in vouchers or staff_loan_voucher_issuances): a"
                    + " database that holds GetMore's real coupons, or has issued for its real store, never"
                    + " simulates. Set GETMORE_MODE=LIVE or OFF.");
        }
        if (mode != GetMoreMode.SIMULATED) {
            long simulatedCoupons = simCouponRepository.count();
            if (simulatedCoupons > 0) {
                log.warn("GetMore is {} but simulated store data is present on this host ({} coupon(s) in"
                        + " getmore_sim_coupons): this database was used by the staging simulator", mode,
                        simulatedCoupons);
            }
        }
        if (mode == GetMoreMode.LIVE) {
            long simulatedVouchers = voucherRepository.countByStoreMode(GetMoreMode.SIMULATED);
            if (simulatedVouchers > 0) {
                log.warn("GetMore is LIVE and {} simulated GetMore voucher(s) are on record: they are never synced"
                        + " here and are left out of the reports", simulatedVouchers);
            }
        }
        merchantWarning(mode, merchantRepository.findByStaffLoanMerchantTrue().orElse(null)).ifPresent(log::warn);
    }

    /** The mode the checks ran for. */
    public GetMoreMode mode() {
        return mode;
    }

    /**
     * Why an accepted Staff Grocery Loan would wait for a voucher no one issues, if it would: GetMore is on and the
     * loan's merchant is not a GetMore store (its {@code voucher_store} is a record, changed only by a migration).
     *
     * @param staffLoanMerchant the Staff Grocery Loan's merchant now, or null when none is set
     */
    public static Optional<String> merchantWarning(GetMoreMode mode, Merchant staffLoanMerchant) {
        if (mode == GetMoreMode.OFF || staffLoanMerchant == null
                || staffLoanMerchant.getVoucherStore() == VoucherStore.GETMORE) {
            return Optional.empty();
        }
        return Optional.of("Accepted loans will wait AWAITING_DISBURSEMENT with no voucher: the Staff Grocery Loan"
                + " merchant " + staffLoanMerchant.getMerchantCode() + " is not a GetMore store");
    }
}
