package zw.co.reikan.loans.core.loan;

public enum LoanPurpose {
    PURCHASE_OF_ASSETS("A", "Purchase of Assets"),
    BUSINESS_EXPANSION("B", "Business Expansion"),
    CAPEX("C", "Capex"),
    DEBT_REFINANCING("DR", "Debt Refinancing"),
    HOME_IMPROVEMENT("HI", "Home Improvement"),
    MEDICAL("MED", "Medical"),
    PREMIUMS("P", "Premiums"),
    PERSONAL_USE("PU", "Personal Use"),
    SCHOOL_FEES("SC", "School Fees"),
    VACATION("V", "Vacation"),
    GENERAL_WORKING_CAPITAL("W", "General Working Capital"),
    WEDDING_EXPENSES("WE", "Wedding Expenses");

    private final String codeId;
    private final String description;

    LoanPurpose(String codeId, String description) {
        this.codeId = codeId;
        this.description = description;
    }

    public String getCodeId() {
        return codeId;
    }

    public String getDescription() {
        return description;
    }
}