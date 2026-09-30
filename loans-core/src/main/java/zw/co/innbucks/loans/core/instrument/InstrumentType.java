package zw.co.innbucks.loans.core.instrument;

/** What an applicant signs electronically (FR-SSB-013). */
public enum InstrumentType {

    LOAN_AGREEMENT("loan agreement", "loanAgreementVersion"),
    SSB_DEDUCTION_AUTHORITY("SSB deduction authority", "deductionAuthorityVersion");

    private final String label;
    private final String versionField;

    InstrumentType(String label, String versionField) {
        this.label = label;
        this.versionField = versionField;
    }

    /** What the applicant calls it: "loan agreement". */
    public String label() {
        return label;
    }

    /** The application field that names the version the applicant accepted. */
    public String versionField() {
        return versionField;
    }
}
