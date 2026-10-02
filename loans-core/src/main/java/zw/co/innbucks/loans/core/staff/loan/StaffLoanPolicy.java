package zw.co.innbucks.loans.core.staff.loan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.instrument.StaffLoanTerms;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The Staff Grocery Loan's terms, from the settings: which amounts may be taken from an offer (FR-SGL-012), when it is
 * due, and what is owed (FR-SGL-026). Interest-free and without fees, so what is owed is what is borrowed.
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

    /**
     * The terms {@code member} would accept borrowing {@code amount} on {@code acceptedDay}, for a voucher spent at
     * {@code merchantName}, as the agreement names them.
     */
    public StaffLoanTerms.Signing signing(StaffMember member, BigDecimal amount, LocalDate acceptedDay,
                                          String merchantName) {
        return new StaffLoanTerms.Signing(member.getFullName(), member.getEmployeeNumber(), member.getNationalId(),
                member.getMsisdn(), member.getDepartment(), money(amount), currency(), INTEREST_RATE,
                totalRepayable(amount), dueDate(acceptedDay), merchantName, voucherValidityDays(),
                unredeemedVoucherTreatment().terms(), acceptedDay);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
