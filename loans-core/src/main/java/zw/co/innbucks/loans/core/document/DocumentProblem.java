package zw.co.innbucks.loans.core.document;

/**
 * Why one uploaded document was not accepted, in words the applicant can act on.
 *
 * @param field        the request field it arrived in, so a client can show the message beside it
 * @param documentType which document it is
 * @param reason       a stable code a client can branch on
 * @param message      what is wrong and what to do about it
 */
public record DocumentProblem(String field, DocumentType documentType, DocumentProblemReason reason,
                              String message) {
}
