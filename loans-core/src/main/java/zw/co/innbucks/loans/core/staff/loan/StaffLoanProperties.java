package zw.co.innbucks.loans.core.staff.loan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Staff Grocery Loan's terms as a borrower takes it up in the SuperApp. The partial draw (FR-SGL-012, OQ-03), the
 * due date (BRD 3.7's design conflict, OQ-02) and the unredeemed-voucher treatment (OQ-09) are open questions for the
 * business, so each is a setting with the working answer as its default.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "loans.staff-loans")
public class StaffLoanProperties {

    /**
     * The least a borrower may take (FR-SGL-012). An offer below it can only be taken in full.
     */
    @NotNull
    @DecimalMin(value = "0.01", message = "loans.staff-loans.minimum-draw must be at least 0.01")
    @Digits(integer = 17, fraction = 2, message = "loans.staff-loans.minimum-draw has at most 2 decimals")
    private BigDecimal minimumDraw = new BigDecimal("10.00");

    /**
     * The steps an amount below the full offer is chosen in (FR-SGL-012): a multiple of this. The full offer can always
     * be taken, whatever it is a multiple of.
     */
    @NotNull
    @DecimalMin(value = "0.01", message = "loans.staff-loans.draw-increment must be at least 0.01")
    @Digits(integer = 17, fraction = 2, message = "loans.staff-loans.draw-increment has at most 2 decimals")
    private BigDecimal drawIncrement = new BigDecimal("5.00");

    /**
     * The day of the month after acceptance the loan is due and collected from salary. The 20th, the day salary is
     * credited (BRD 3.7 recommends it over the 19th, which would put every loan a day in arrears before the money to
     * repay it exists). At most the 28th, so every month has it.
     */
    @Min(value = 1, message = "loans.staff-loans.repayment-day must be between 1 and 28")
    @Max(value = 28, message = "loans.staff-loans.repayment-day must be between 1 and 28")
    private int repaymentDay = 20;

    /** Days after the due date before an unpaid loan counts as in arrears (FR-SGL-013, FR-SGL-014). */
    @Min(value = 0, message = "loans.staff-loans.arrears-grace-days must be 0 or more")
    @Max(value = 60, message = "loans.staff-loans.arrears-grace-days must be at most 60")
    private int arrearsGraceDays = 0;

    /**
     * Days past the due date after which a loan still unpaid is escalated to Credit in the daily arrears report
     * (BRD 3.8: "escalation to Credit after a configurable number of days").
     */
    @Min(value = 1, message = "loans.staff-loans.arrears-escalation-days must be at least 1")
    @Max(value = 365, message = "loans.staff-loans.arrears-escalation-days must be at most 365")
    private int arrearsEscalationDays = 30;

    /**
     * When the daily arrears report is emailed to Credit and Human Capital (FR-SGL-045), on the market's clock: 07:00
     * by default. Only where the Staff Grocery Loan jobs run; the report can always be read in the portal.
     */
    private String arrearsReportCron = "0 0 7 * * *";

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "loans.staff-loans.currency must be a 3-letter ISO code, such as USD")
    private String currency = "USD";

    /** OQ-09, until Finance and Legal decide. */
    @NotNull
    private UnredeemedVoucherTreatment unredeemedVoucherTreatment = UnredeemedVoucherTreatment.DEBT_STANDS;

    /**
     * Payroll's mailboxes (FR-SGL-007, BRD 3.8): told, with Human Capital, when a borrower leaves owing a paid-out loan,
     * so it is recovered from their terminal benefits. Empty, Human Capital alone is told, and the boot log says so.
     */
    @NotNull
    private List<@Email(message = "loans.staff-loans.payroll-emails must be email addresses") String> payrollEmails =
            new ArrayList<>();

    /** How a loan accepted from now on is paid out, and for a cash payout, where and how the bank books it. */
    @Valid
    @NotNull
    private Payout payout = new Payout();

    @AssertTrue(message = "loans.staff-loans.arrears-report-cron is not a valid cron expression (second minute hour"
            + " day month weekday, e.g. 0 0 7 * * *)")
    public boolean isArrearsReportCronValid() {
        return CronExpression.isValidExpression(arrearsReportCron);
    }

    @AssertTrue(message = "loans.staff-loans.minimum-draw must be a multiple of loans.staff-loans.draw-increment")
    public boolean isMinimumDrawAStep() {
        return minimumDraw == null || drawIncrement == null || drawIncrement.signum() <= 0
                || minimumDraw.remainder(drawIncrement).signum() == 0;
    }

    /**
     * How a Staff Grocery Loan accepted from now on is paid out (loans.staff-loans.payout). Each loan keeps the method,
     * destination and account it was accepted under, so a change here reaches only loans accepted after it. CASH is
     * refused at boot without a destination and a product; {@link StaffLoanPayoutBootCheck} refuses it without the
     * InnBucks credentials too.
     */
    @Data
    public static class Payout {

        /** The pre-approved booking types the bank takes; {@link #effectiveLoanType()} is one of them. */
        public static final String PERSONAL = "PERSONAL";
        public static final String CONSUMER_FINANCE = "CONSUMER_FINANCE";

        /**
         * VOUCHER: as before (the loan waits for the voucher disbursement; nothing is sent to the bank). CASH: paid into
         * the borrower's own account by the bank as soon as the acceptance commits.
         */
        @NotNull(message = "loans.staff-loans.payout.method must be VOUCHER or CASH")
        private StaffLoanPayoutMethod method = StaffLoanPayoutMethod.VOUCHER;

        /** CASH only: VEENGU_WALLET or BR_ACCOUNT. No default: the business decides. */
        private StaffLoanPayoutDestination destination;

        /** CASH only: the bank's product code for the Staff Grocery Loan (NANOUS is the SSB loan's). */
        private String product = "";

        /**
         * The booking's {@code type}. Blank: PERSONAL for a VEENGU_WALLET payout (what a wallet payee is booked as
         * today); a BR_ACCOUNT payout needs it set, until the bank says which it takes.
         */
        @Pattern(regexp = "(PERSONAL|CONSUMER_FINANCE)?",
                message = "loans.staff-loans.payout.loan-type must be PERSONAL or CONSUMER_FINANCE")
        private String loanType;

        /** Sent to the bank as the borrower's {@code employer}; blank, it is left out. */
        private String employerName = "InnBucks MicroBank";

        /**
         * A payout claimed this long ago and never settled (the process died mid-call) is held IN_DOUBT for the
         * bank's inquiry, never sent again.
         */
        @Min(value = 1, message = "loans.staff-loans.payout.stale-claim-minutes must be at least 1")
        private int staleClaimMinutes = 30;

        /** How often the bank is asked about payouts it took (BOOKED) or may have taken (IN_DOUBT). */
        @NotNull(message = "loans.staff-loans.payout.status-check-interval is required")
        private Duration statusCheckInterval = Duration.ofMinutes(3);

        /**
         * A payout is not sent once its loan's due date is fewer than this many days away: the borrower would repay it
         * almost as soon as it landed. Credit cancels it, or the borrower applies again.
         */
        @Min(value = 0, message = "loans.staff-loans.payout.min-days-before-due must be 0 or more")
        @Max(value = 31, message = "loans.staff-loans.payout.min-days-before-due must be at most 31")
        private int minDaysBeforeDue = 7;

        /**
         * How long after the bank's inquiry first said it holds no loan under a reference a person may confirm that
         * payout was never booked: time for a booking still on its way to show.
         */
        @NotNull(message = "loans.staff-loans.payout.not-booked-min-age is required")
        private Duration notBookedMinAge = Duration.ofHours(24);

        /** How long a payout confirmed never booked is still asked about, in case the bank booked it after all. */
        @NotNull(message = "loans.staff-loans.payout.failed-watch is required")
        private Duration failedWatch = Duration.ofDays(14);

        /** The bank's statuses (loanDetails.status, any case) that mean the money is in the borrower's account. */
        @NotNull
        private List<String> paidStatuses = new ArrayList<>(List.of("SETTLED"));

        /** The bank's statuses that mean it holds the loan and has not paid it yet. Any other is logged and audited. */
        @NotNull
        private List<String> pendingStatuses = new ArrayList<>(List.of("PROCESSING", "PENDING", "APPROVED", "BOOKED"));

        /**
         * The bank's statuses that mean it holds the loan and did not pay it, and will not: once it has said so for
         * {@code not-booked-min-age}, a SUPER_ADMIN may record the payout as never paid, which cancels the loan.
         */
        @NotNull
        private List<String> failedStatuses = new ArrayList<>(List.of("REJECTED", "CANCELLED", "FAILED"));

        public boolean cash() {
            return method == StaffLoanPayoutMethod.CASH;
        }

        /** The booking's {@code type}: the one set, else PERSONAL for a VEENGU_WALLET payout; null when neither. */
        public String effectiveLoanType() {
            if (loanType != null && !loanType.isBlank()) {
                return loanType.strip();
            }
            return destination == StaffLoanPayoutDestination.VEENGU_WALLET ? PERSONAL : null;
        }

        /** Whether the bank's {@code status} means the money is in the account. */
        public boolean isPaidStatus(String status) {
            return contains(paidStatuses, status);
        }

        /** Whether the bank's {@code status} means it holds the loan and has not paid it yet. */
        public boolean isPendingStatus(String status) {
            return contains(pendingStatuses, status);
        }

        /** Whether the bank's {@code status} means it holds the loan and did not pay it, and will not. */
        public boolean isFailedStatus(String status) {
            return contains(failedStatuses, status);
        }

        @AssertTrue(message = "loans.staff-loans.payout.method CASH needs loans.staff-loans.payout.destination"
                + " (VEENGU_WALLET or BR_ACCOUNT) and loans.staff-loans.payout.product")
        public boolean isCashConfigured() {
            return !cash() || (destination != null && product != null && !product.isBlank());
        }

        @AssertTrue(message = "loans.staff-loans.payout.destination BR_ACCOUNT needs loans.staff-loans.payout.loan-type"
                + " (PERSONAL or CONSUMER_FINANCE): ask the bank which type pays an account")
        public boolean isAccountLoanTypeSet() {
            return !cash() || destination != StaffLoanPayoutDestination.BR_ACCOUNT || effectiveLoanType() != null;
        }

        @AssertTrue(message = "loans.staff-loans.payout.status-check-interval, not-booked-min-age and failed-watch must"
                + " be positive")
        public boolean isDurationsPositive() {
            return positive(statusCheckInterval) && positive(notBookedMinAge) && positive(failedWatch);
        }

        @AssertTrue(message = "loans.staff-loans.payout.paid-statuses must name at least one status, and no status may"
                + " be in more than one of paid-statuses, pending-statuses and failed-statuses")
        public boolean isBankStatusesDistinct() {
            if (paidStatuses == null || pendingStatuses == null || failedStatuses == null
                    || paidStatuses.stream().noneMatch(status -> status != null && !status.isBlank())) {
                return false;
            }
            return paidStatuses.stream().noneMatch(status -> isPendingStatus(status) || isFailedStatus(status))
                    && pendingStatuses.stream().noneMatch(this::isFailedStatus);
        }

        private static boolean positive(Duration duration) {
            return duration == null || (!duration.isNegative() && !duration.isZero());
        }

        private static boolean contains(List<String> statuses, String status) {
            if (statuses == null || status == null || status.isBlank()) {
                return false;
            }
            String wanted = status.strip().toUpperCase(Locale.ROOT);
            return statuses.stream().anyMatch(s -> s != null && s.strip().toUpperCase(Locale.ROOT).equals(wanted));
        }
    }
}
