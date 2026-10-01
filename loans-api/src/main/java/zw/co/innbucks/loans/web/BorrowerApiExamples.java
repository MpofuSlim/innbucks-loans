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

    // ---- The SuperApp inbox and offer messages (FR-SGL-019, FR-SGL-020, FR-SGL-022) ----
    // Chipo Banda's inbox on the morning of 1 October, before she takes up offer 31: its notification from the run of
    // Monday 28 September, unread, and the one for offer 12 from the run of 14 September, read, which lapsed.

    private static final String INBOX_1103 = """
                  "id": 1103,
                  "kind": "OFFER_NEW",
                  "title": "Your Staff Grocery Loan offer",
                  "message": "InnBucks Staff Grocery Loan. You have a new offer of up to USD 300.00, open until 08.00 on 5 Oct 2026. Log in to the InnBucks app to accept it.",
                  "createdAt": "2026-09-28T08:00:01+02:00",""";

    public static final String INBOX = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
            """ + INBOX_1103 + """

                    "offerId": 31,
                    "offerOpen": true
                  },
                  {
                    "id": 877,
                    "kind": "OFFER_NEW",
                    "title": "Your Staff Grocery Loan offer",
                    "message": "InnBucks Staff Grocery Loan. You have a new offer of up to USD 300.00, open until 08.00 on 21 Sep 2026. Log in to the InnBucks app to accept it.",
                    "createdAt": "2026-09-14T08:00:01+02:00",
                    "readAt": "2026-09-14T17:22:40+02:00",
                    "offerId": 12,
                    "offerOpen": false
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 2,
                "totalPages": 1
              }
            }""";

    public static final String INBOX_UNREAD = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "unread": 1
              }
            }""";

    public static final String INBOX_READ = """
            {
              "code": "OK",
              "message": "Marked read",
              "data": {
            """ + INBOX_1103 + """

                "readAt": "2026-10-01T09:01:55+02:00",
                "offerId": 31,
                "offerOpen": true
              }
            }""";

    public static final String INBOX_READ_ALL = """
            {
              "code": "OK",
              "message": "1 notification marked read",
              "data": {
                "marked": 1
              }
            }""";

    public static final String INBOX_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Notification 999 not found"
            }""";

    public static final String OFFER_MESSAGES = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "optedOut": false
              }
            }""";

    public static final String OFFER_MESSAGES_REQUEST = """
            {
              "optedOut": true
            }""";

    public static final String OFFER_MESSAGES_OPTED_OUT = """
            {
              "code": "OK",
              "message": "Offer messages stopped; offers still appear in the app",
              "data": {
                "optedOut": true,
                "updatedAt": "2026-10-01T09:03:12+02:00"
              }
            }""";
}
