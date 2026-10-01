package zw.co.innbucks.loans.web;

/**
 * Example bodies for SuperApp borrower sign-in, as one story: Chipo Banda (employee E1012, Treasury, phone 263773456789,
 * the staff register's record) signs in to the SuperApp with her PIN and opens the Staff Grocery Loan.
 */
public final class BorrowerApiExamples {

    private BorrowerApiExamples() {
    }

    /** The middleware's assertion, as the SuperApp sends it. Shortened: a real one is a few hundred characters. */
    public static final String EXCHANGE_REQUEST = """
            {
              "assertion": "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJpbm5idWNrcy1taWRkbGV3YXJlIiwiYXVkIjoiaW5uYnVja3MtbGVuZGluZyJ9.c2lnbmF0dXJl"
            }""";

    public static final String SESSION = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJ0b2tlbl91c2UiOiJib3Jyb3dlciJ9.c2lnbmF0dXJl",
                "tokenType": "Bearer",
                "expiresIn": 900,
                "employeeNumber": "E1012",
                "fullName": "Chipo Banda"
              }
            }""";

    public static final String ASSERTION_REJECTED = """
            {
              "code": "ASSERTION_REJECTED",
              "message": "Assertion rejected - sign in to the SuperApp again"
            }""";

    public static final String NOT_ON_STAFF_REGISTER = """
            {
              "code": "NOT_ON_STAFF_REGISTER",
              "message": "The Staff Grocery Loan is for InnBucks staff. Your number is not on the staff register: if you work for InnBucks, ask Human Capital to check your details."
            }""";

    public static final String SIGN_IN_UNAVAILABLE = """
            {
              "code": "BORROWER_SIGN_IN_UNAVAILABLE",
              "message": "SuperApp sign-in to the Staff Grocery Loan is not available on this server"
            }""";

    public static final String ASSERTION_MISSING = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "assertion": "assertion is required"
              }
            }""";

    public static final String TEST_ASSERTION_REQUEST = """
            {
              "phone": "+263773456789",
              "methods": ["pin"]
            }""";

    public static final String TEST_ASSERTION = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "assertion": "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJpbm5idWNrcy1taWRkbGV3YXJlIiwianRpIjoidGVzdC0xIn0.c2lnbmF0dXJl",
                "expiresAt": "2026-10-01T10:02:00+02:00"
              }
            }""";

    public static final String TEST_ASSERTION_KEY_REFUSED = """
            {
              "code": "UNAUTHORIZED",
              "message": "Invalid or missing api key"
            }""";

    public static final String PROFILE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "employeeNumber": "E1012",
                "fullName": "Chipo Banda",
                "maskedMsisdn": "****6789",
                "department": "Treasury",
                "signedInWith": ["pin"]
              }
            }""";
}
