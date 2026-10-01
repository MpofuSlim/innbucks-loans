package zw.co.innbucks.loans.core.borrower;

/**
 * A signed-in borrower's session: send {@code accessToken} as {@code Authorization: Bearer} on the borrower endpoints.
 * It lasts {@code expiresIn} seconds and is not refreshed; the app signs in again with a fresh assertion.
 */
public record BorrowerSession(String accessToken, String tokenType, long expiresIn, String employeeNumber,
                              String fullName) {

    @Override
    public String toString() {
        return "BorrowerSession[employeeNumber=" + employeeNumber + "]";
    }
}
