package zw.co.innbucks.loans.core.staff.loan;

import java.math.BigDecimal;

/**
 * The amounts a borrower may take from an offer (FR-SGL-012): from {@code minimum} to {@code maximum} (the offer) in
 * steps of {@code increment}, or the full offer whatever it is a multiple of.
 */
public record DrawRules(BigDecimal minimum, BigDecimal increment, BigDecimal maximum, String currency) {
}
