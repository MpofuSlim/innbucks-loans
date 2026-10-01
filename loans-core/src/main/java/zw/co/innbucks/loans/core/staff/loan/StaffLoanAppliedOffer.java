package zw.co.innbucks.loans.core.staff.loan;

/** The offer a borrower applying takes up, and whether it was made just now rather than already held. */
public record StaffLoanAppliedOffer(StaffLoanOfferView offer, boolean created) {
}
