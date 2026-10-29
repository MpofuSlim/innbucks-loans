package zw.co.innbucks.loans.core.staff.loan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.instrument.StaffLoanTerms;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The Staff Grocery Loan's terms, from the settings: which amounts may be taken from an offer (FR-SGL-012), when it is
 * due, what is owed (FR-SGL-026), and how a loan accepted now is paid out. Interest-free and without fees, so what is
 * owed is what is borrowed.
 */
@Component
@RequiredArgsConstructor
public class StaffLoanPolicy {

    static final BigDecimal INTEREST_RATE = BigDecimal.ZERO;

    private final StaffLoanProperties properties;
    private final VoucherProperties voucherProperties;

    /** The amounts {@code offerAmount} may be drawn in. An offer under the minimum draw can only be taken in full. */
    public DrawRules drawRules(BigDecimal offerAmount) {
        return new DrawRules(money(properties.getMinimumDraw().min(offerAmount)), money(properties.getDrawIncrement()),
                money(offerAmount), properties.getCurrency());
    }

    /**
     * Why {@code amount} may not be taken from {@code offerAmount}, in words the borrower can act on; empty when it
     * may.
     */
    public Optional<String> amountProblem(BigDecimal offerAmount, BigDecimal amount) {
        DrawRules rules = drawRules(offerAmount);
        String allowed = String.format("Choose an amount from %s %s to %s %s in steps of %s %s", rules.currency(),
                rules.minimum(), rules.currency(), rules.maximum(), rules.currency(), rules.increment());
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            return Optional.of(allowed);
        }
        if (amount.compareTo(offerAmount) == 0) {
            return Optional.empty();
        }
        if (amount.compareTo(offerAmount) > 0) {
            return Optional.of(String.format("You can borrow up to %s %s. %s", rules.currency(), rules.maximum(),
                    allowed));
        }
        if (amount.compareTo(rules.minimum()) < 0 || amount.remainder(rules.increment()).signum() != 0) {
            return Optional.of(allowed);
        }
        return Optional.empty();
    }

    /** The day a loan accepted on {@code acceptedDay} is due: the repayment day of the following month. */
    public LocalDate dueDate(LocalDate acceptedDay) {
        return acceptedDay.plusMonths(1).withDayOfMonth(properties.getRepaymentDay());
    }

    /** What is owed for borrowing {@code amount}: the same, since there is no interest and no fee (OQ-04). */
    public BigDecimal totalRepayable(BigDecimal amount) {
        return money(amount);
    }

    public String currency() {
        return properties.getCurrency();
    }

    public int voucherValidityDays() {
        return voucherProperties.getValidityDays();
    }

    public UnredeemedVoucherTreatment unredeemedVoucherTreatment() {
        return properties.getUnredeemedVoucherTreatment();
    }

    public int arrearsGraceDays() {
        return properties.getArrearsGraceDays();
    }

    /** How a loan accepted now is paid out; each loan keeps the way it was accepted under. */
    public StaffLoanPayoutMethod payoutMethod() {
        return properties.getPayout().getMethod();
    }

    /** Where a loan accepted now is paid when it is paid in cash; null when none is set. */
    public StaffLoanPayoutDestination payoutDestination() {
        return properties.getPayout().getDestination();
    }

    /**
     * Where a cash loan {@code member} accepts now is paid, as the register holds it now: their registered phone for
     * VEENGU_WALLET, the register's account number for BR_ACCOUNT ({@link StaffLoanPayoutDestination#accountOf}).
     * Empty when loans are paid by voucher, or when the register holds no such account for them: a member whose
     * register entry holds a wallet has no account to be paid into.
     */
    public Optional<PayoutTarget> payoutTarget(StaffMember member) {
        StaffLoanProperties.Payout payout = properties.getPayout();
        if (!payout.cash() || payout.getDestination() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(payout.getDestination().accountOf(member))
                .map(account -> new PayoutTarget(payout.getDestination(), account));
    }

    /**
     * The terms {@code member} would accept borrowing {@code amount} on {@code acceptedDay}, for a voucher spent at
     * {@code merchantName}, as the agreement names them.
     */
    public StaffLoanTerms.Signing signing(StaffMember member, BigDecimal amount, LocalDate acceptedDay,
                                          String merchantName) {
        return signing(member, amount, acceptedDay, merchantName, null);
    }

    /**
     * The terms {@code member} would accept borrowing {@code amount} on {@code acceptedDay}, as the agreement names
     * them: with {@code payout} null, for a voucher spent at {@code merchantName}; with it, paid in cash into that
     * account, and nothing about a voucher (the caller passes no merchant).
     */
    public StaffLoanTerms.Signing signing(StaffMember member, BigDecimal amount, LocalDate acceptedDay,
                                          String merchantName, PayoutTarget payout) {
        boolean cash = payout != null;
        return new StaffLoanTerms.Signing(member.getFullName(), member.getEmployeeNumber(), member.getNationalId(),
                member.getMsisdn(), member.getDepartment(), money(amount), currency(), INTEREST_RATE,
                totalRepayable(amount), dueDate(acceptedDay), merchantName, cash ? 0 : voucherValidityDays(),
                cash ? null : unredeemedVoucherTreatment().terms(), cash ? payout.named() : null, acceptedDay);
    }

    /**
     * The account a cash loan is paid into, and its kind. Shown and written only by its last four digits; the
     * account itself is kept on the loan alone.
     */
    public record PayoutTarget(StaffLoanPayoutDestination destination, String account) {

        /** {@code ****3123}. */
        public String masked() {
            return MsisdnUtils.mask(account);
        }

        /** {@code 3123}. */
        public String last4() {
            return MsisdnUtils.lastFourDigits(account);
        }

        /** How the agreement names it: {@code InnBucks wallet ****3123}. */
        public String named() {
            return destination.label() + " " + masked();
        }

        @Override
        public String toString() {
            return "PayoutTarget[" + destination + " " + masked() + "]";
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
