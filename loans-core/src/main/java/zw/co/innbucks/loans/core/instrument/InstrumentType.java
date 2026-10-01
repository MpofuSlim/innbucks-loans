package zw.co.innbucks.loans.core.instrument;

import java.util.List;
import java.util.Map;

/**
 * What a borrower accepts electronically: an SSB application's loan agreement and deduction authority (FR-SSB-013),
 * and the Staff Grocery Loan agreement accepted in the SuperApp (FR-SGL-027).
 */
public enum InstrumentType {

    LOAN_AGREEMENT("loan agreement", "loanAgreementVersion"),
    SSB_DEDUCTION_AUTHORITY("SSB deduction authority", "deductionAuthorityVersion"),
    /**
     * Accepted with the loan in the SuperApp, never with an SSB application: it is not in {@link #ssbApplication()}.
     */
    STAFF_GROCERY_LOAN_AGREEMENT("Staff Grocery Loan agreement", "agreementVersion");

    private static final List<InstrumentType> SSB_APPLICATION = List.of(LOAN_AGREEMENT, SSB_DEDUCTION_AUTHORITY);

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

    /** The request field that names the version the applicant accepted. */
    public String versionField() {
        return versionField;
    }

    /** The instruments an SSB application is signed against, in the order they are signed. */
    public static List<InstrumentType> ssbApplication() {
        return SSB_APPLICATION;
    }

    /** The {@code {{placeholder}}}s this instrument's wording may use, with what each is filled with. */
    public Map<String, String> placeholders() {
        return this == STAFF_GROCERY_LOAN_AGREEMENT ? StaffLoanTerms.placeholders() : InstrumentTerms.placeholders();
    }

    /** The placeholders in {@code body} this instrument cannot fill, in order of first appearance. */
    List<String> unknownPlaceholders(String body) {
        return this == STAFF_GROCERY_LOAN_AGREEMENT ? StaffLoanTerms.unknownPlaceholders(body)
                : InstrumentTerms.unknownPlaceholders(body);
    }
}
