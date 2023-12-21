package zw.co.reikan.loans.core.loan;

public enum BankAccountType {
    SAVINGS("Savings Account"),
    CURRENT("Current Account");

    private final String displayName;

    BankAccountType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
