package zw.co.innbucks.loans.core.staff.loan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.instrument.StaffLoanTerms;
import zw.co.innbucks.loans.core.staff.StaffMember;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Staff Grocery Loan's terms, from the settings: which amounts may be taken from an offer (FR-SGL-012), when it is
 * due, what is owed (FR-SGL-026), and the InnBucks wallet a loan accepted now is paid into. Interest-free and without
 * fees, so what is owed is what is borrowed.
 */
@Component
@RequiredArgsConstructor
public class StaffLoanPolicy {

    static final BigDecimal INTEREST_RATE = BigDecimal.ZERO;

    private final StaffLoanProperties properties;

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

    public int arrearsGraceDays() {
        return properties.getArrearsGraceDays();
    }

    /**
     * Where a loan {@code member} accepts now is paid: their InnBucks (Veengu) wallet, the registered phone they prove
     * with their PIN. Empty when the register holds no wallet-shaped phone for them ({@link PayoutTarget#WALLET}).
     */
    public Optional<PayoutTarget> payoutTarget(StaffMember member) {
        if (member == null || member.getMsisdn() == null || !PayoutTarget.WALLET.matcher(member.getMsisdn()).matches()) {
            return Optional.empty();
        }
        return Optional.of(new PayoutTarget(member.getMsisdn()));
    }

    /**
     * The terms {@code member} would accept borrowing {@code amount} on {@code acceptedDay}, paid into {@code payout},
     * as the agreement names them.
     */
    public StaffLoanTerms.Signing signing(StaffMember member, BigDecimal amount, LocalDate acceptedDay,
                                          PayoutTarget payout) {
        return new StaffLoanTerms.Signing(member.getFullName(), member.getEmployeeNumber(), member.getNationalId(),
                member.getMsisdn(), member.getDepartment(), money(amount), currency(), INTEREST_RATE,
                totalRepayable(amount), dueDate(acceptedDay), payout.named(), acceptedDay);
    }

    /**
     * The InnBucks wallet a loan is paid into: the borrower's registered phone. Shown and written only by its last four
     * digits; the number itself is kept on the loan alone.
     */
    public record PayoutTarget(String account) {

        /** How the borrower is told where the money goes: "your InnBucks wallet ending 3123". */
        public static final String LABEL = "InnBucks wallet";

        /** A wallet: a Zimbabwean mobile number as the register stores it, 2637XXXXXXXX. */
        static final Pattern WALLET = Pattern.compile("2637[13789]\\d{7}");

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
            return LABEL + " " + masked();
        }

        @Override
        public String toString() {
            return "PayoutTarget[" + masked() + "]";
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
