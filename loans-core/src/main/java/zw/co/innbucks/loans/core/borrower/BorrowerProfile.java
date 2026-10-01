package zw.co.innbucks.loans.core.borrower;

import java.util.List;

/** Who a borrower session is for, as the SuperApp shows it. */
public record BorrowerProfile(String employeeNumber, String fullName, String maskedMsisdn, String department,
                              List<String> signedInWith) {
}
