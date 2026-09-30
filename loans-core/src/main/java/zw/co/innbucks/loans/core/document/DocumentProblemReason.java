package zw.co.innbucks.loans.core.document;

/** Why an uploaded document was not accepted (FR-SSB-005). */
public enum DocumentProblemReason {
    /** Not valid base64: the upload was damaged before it arrived. */
    INVALID_ENCODING,
    /** Nothing in it. */
    EMPTY,
    /** A program, not a document. */
    EXECUTABLE,
    /** Not a format accepted for this document. */
    UNSUPPORTED_TYPE,
    /** Over the size limit. */
    TOO_LARGE,
    /** Cannot be opened: damaged or cut off. */
    DAMAGED,
    /** A PDF that needs a password to open. */
    PASSWORD_PROTECTED,
    /** A PDF with no pages. */
    NO_PAGES,
    /** A PDF with more pages than accepted. */
    TOO_MANY_PAGES,
    /** An image too small to read. */
    RESOLUTION_TOO_LOW,
    /** An image too large to process. */
    DIMENSIONS_TOO_LARGE,
    /** An image of one flat tone: blank, or too dark or too bright. */
    BLANK,
    /** An image too blurred to read. */
    BLURRED
}
