package zw.co.innbucks.loans.core.staff.loan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.borrower.BorrowerProperties;
import zw.co.innbucks.loans.core.config.HttpClientConfig;
import zw.co.innbucks.loans.core.config.SingleFlightTokenCache;
import zw.co.innbucks.loans.core.disbursements.InnbucksParameters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Says at boot how Staff Grocery Loans are paid out, and refuses to start a cash payout that could not work. The
 * settings' own rules ({@link StaffLoanProperties.Payout}: a destination, a product, a loan type for an account) are
 * checked when they are bound; this adds what they cannot see: the InnBucks credentials the bank is called with, an
 * inquiry endpoint that names the payout's reference, and a {@code stale-claim-minutes} no shorter than a booking can
 * take (a payout still being booked must never be held IN_DOUBT under the call that is booking it). A misconfigured cash
 * payout fails here, at deploy, rather than on the first borrower who accepts a loan.
 */
@Slf4j
@Component
public class StaffLoanPayoutBootCheck {

    static final String REFERENCE_PLACEHOLDER = "{participantReference}";

    public StaffLoanPayoutBootCheck(StaffLoanProperties properties, InnbucksParameters innbucks,
                                    BorrowerProperties borrower, HttpClientConfig http) {
        StaffLoanProperties.Payout payout = properties.getPayout();
        if (!payout.cash()) {
            log.info("[startup] Staff Grocery Loans are paid by voucher (STAFF_LOANS_PAYOUT_METHOD=VOUCHER): nothing is"
                    + " sent to the bank");
            return;
        }
        List<String> missing = new ArrayList<>();
        requireSet(innbucks.getApiKey(), "innbucks.api-key (INNBUCKS_API_KEY)", missing);
        requireSet(innbucks.getUsername(), "innbucks.username (INNBUCKS_USERNAME)", missing);
        requireSet(innbucks.getPassword(), "innbucks.password (INNBUCKS_PASSWORD)", missing);
        requireSet(innbucks.getAuthEndpoint(), "innbucks.auth-endpoint", missing);
        requireSet(innbucks.getCreateLoanAccountEndpoint(), "innbucks.create-loan-account-endpoint", missing);
        if (innbucks.getLoanInquiryEndpoint() == null
                || !innbucks.getLoanInquiryEndpoint().contains(REFERENCE_PLACEHOLDER)) {
            missing.add("innbucks.loan-inquiry-endpoint with " + REFERENCE_PLACEHOLDER);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Staff Grocery Loans are set to be paid in cash"
                    + " (STAFF_LOANS_PAYOUT_METHOD=CASH), which books them with the bank, but " + String.join(", ",
                    missing) + (missing.size() == 1 ? " is" : " are") + " not set");
        }
        Duration booking = longestBooking(http);
        if (Duration.ofMinutes(payout.getStaleClaimMinutes()).compareTo(booking) < 0) {
            throw new IllegalStateException("loans.staff-loans.payout.stale-claim-minutes"
                    + " (STAFF_LOANS_PAYOUT_STALE_CLAIM_MINUTES) is " + payout.getStaleClaimMinutes() + ", shorter"
                    + " than a booking can take (" + booking.toSeconds() + "s: two logins and two posts at the"
                    + " http.client timeouts): a payout still being booked would be held IN_DOUBT as abandoned. Set it"
                    + " to at least " + booking.plusSeconds(59).toMinutes() + ".");
        }
        log.info("[startup] Staff Grocery Loans are paid in cash into the borrower's {} ({}), product {}, type {}, as"
                        + " soon as they accept", payout.getDestination().label(), payout.getDestination(),
                payout.getProduct().strip(), payout.effectiveLoanType());
        if (borrower.getTestAssertions().isEnabled()) {
            log.error("[startup] Staff Grocery Loans are paid in CASH while loans.borrower.test-assertions.enabled is"
                    + " true: whoever holds the test-assertion key can accept a loan as any staff member, and it is"
                    + " paid out. Staging only.");
        }
    }

    /**
     * The longest one booking can take: a login (or the wait for someone else's), the post, and after a 401 another
     * login and the post again, each bounded by the http.client timeouts.
     */
    static Duration longestBooking(HttpClientConfig http) {
        Duration post = Duration.ofMillis((long) http.getConnectTimeout() + http.getReadTimeout());
        Duration login = SingleFlightTokenCache.waitBound(http.getConnectTimeout(), http.getReadTimeout());
        return post.multipliedBy(2).plus(login.multipliedBy(2));
    }

    private static void requireSet(String value, String key, List<String> missing) {
        if (value == null || value.isBlank()) {
            missing.add(key);
        }
    }
}
