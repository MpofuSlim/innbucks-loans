package zw.co.innbucks.loans.core.staff.loan;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** An offer as the borrower sees it (FR-SGL-025): what they can borrow, until when, and in which amounts. */
public record StaffLoanOfferView(Long offerId, BigDecimal amount, String currency, LocalDateTime expiresAt,
                                 DrawRules draw) {
}
