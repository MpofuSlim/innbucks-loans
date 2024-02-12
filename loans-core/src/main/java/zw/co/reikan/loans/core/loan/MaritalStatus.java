package zw.co.reikan.loans.core.loan;

public enum MaritalStatus {
    SINGLE("Single","S" ),
    MARRIED("Married", "M"),
    DIVORCED("Divorced", "D"),
    WIDOWED("Widowed", "W");

    private final String displayName;
    private final String code;

    public String getCode() {
        return code;
    }

    MaritalStatus(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String getDisplayName() {
        return displayName;
    }
}
