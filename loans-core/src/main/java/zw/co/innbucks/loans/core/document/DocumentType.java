package zw.co.innbucks.loans.core.document;

/**
 * The documents an application carries (FR-SSB-009). A payslip or national ID can be replaced by a new
 * version, for instance when Credit asks for a clearer one; a signature cannot, since it belongs to the
 * application that was signed.
 */
public enum DocumentType {

    PAYSLIP("payslipPicture", true, true),
    NATIONAL_ID("nationalIdPicture", true, true),
    SIGNATURE("signature", false, false),
    WITNESS_SIGNATURE("witness.signature", false, false);

    private final String fieldName;
    private final boolean kycDocument;
    private final boolean amendable;

    DocumentType(String fieldName, boolean kycDocument, boolean amendable) {
        this.fieldName = fieldName;
        this.kycDocument = kycDocument;
        this.amendable = amendable;
    }

    /** The application field it arrives in, used to name it in an error. */
    public String fieldName() {
        return fieldName;
    }

    /** A KYC document must be a PDF, PNG, JPEG or GIF; a signature need only not be an executable. */
    public boolean kycDocument() {
        return kycDocument;
    }

    public boolean amendable() {
        return amendable;
    }
}
