package zw.co.innbucks.loans.core.document;

import zw.co.innbucks.loans.core.files.FileSignatureValidator.FileKind;

import java.util.Set;

/**
 * The documents an application carries (FR-SSB-009). A payslip or national ID can be replaced by a new
 * version, for instance when Credit asks for a clearer one; a signature cannot, since it belongs to the
 * application that was signed.
 */
public enum DocumentType {

    PAYSLIP("payslipPicture", "payslip", true, true),
    NATIONAL_ID("nationalIdPicture", "national ID", true, true),
    SIGNATURE("signature", "signature", false, false),
    WITNESS_SIGNATURE("witness.signature", "witness's signature", false, false);

    private static final Set<FileKind> DOCUMENT_KINDS = Set.of(FileKind.PDF, FileKind.PNG, FileKind.JPEG, FileKind.GIF);
    private static final Set<FileKind> IMAGE_KINDS = Set.of(FileKind.PNG, FileKind.JPEG, FileKind.GIF);

    private final String fieldName;
    private final String label;
    private final boolean kycDocument;
    private final boolean amendable;

    DocumentType(String fieldName, String label, boolean kycDocument, boolean amendable) {
        this.fieldName = fieldName;
        this.label = label;
        this.kycDocument = kycDocument;
        this.amendable = amendable;
    }

    /** The application field it arrives in, used to name it in an error. */
    public String fieldName() {
        return fieldName;
    }

    /** What the applicant calls it: "payslip", "national ID". */
    public String label() {
        return label;
    }

    /** A payslip or national ID: a document to be read, held to the reading checks (size, sharpness). */
    public boolean kycDocument() {
        return kycDocument;
    }

    /** A payslip or national ID may be a PDF or a photo; a signature is an image. */
    public Set<FileKind> acceptedKinds() {
        return kycDocument ? DOCUMENT_KINDS : IMAGE_KINDS;
    }

    public boolean amendable() {
        return amendable;
    }
}
