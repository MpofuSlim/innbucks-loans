package zw.co.innbucks.loans.web;

/**
 * Example bodies for writing off Staff Grocery Loans (FR-GEN-011), continuing the staff loan examples: Nyasha Dube
 * (E1001) resigned in October with SGL-2026-000151 (USD 250.00) paid out and flagged for recovery from her terminal
 * benefits; voucher 8 expired on 7 November. On Tuesday 15 December credit1 proposes write-off request 1, and
 * finance1 approves it: the loan is WRITTEN_OFF. Chipo Banda's SGL-2026-000143 cannot be written off on 4 November,
 * because voucher 7 can still be spent.
 */
public final class StaffLoanWriteOffApiExamples {

    private StaffLoanWriteOffApiExamples() {
    }

    public static final String PROPOSAL = """
            {
              "staffLoanId": 151,
              "kind": "WRITE_OFF",
              "reason": "Resigned in October; terminal benefits did not cover the balance and recovery has failed"
            }""";

    private static final String REQUEST_1_HEAD = """
            {
                "id": 1,
                "staffLoanId": 151,
                "loanReference": "SGL-2026-000151",
                "employeeNumber": "E1001",
                "fullName": "Nyasha Dube",""";

    private static final String REQUEST_1_TERMS = """

                "kind": "WRITE_OFF",
                "amount": 250.00,
                "currency": "USD",
                "reason": "Resigned in October; terminal benefits did not cover the balance and recovery has failed",""";

    private static final String REQUEST_1_APPROVED = REQUEST_1_HEAD + """

                "loanStatus": "WRITTEN_OFF",""" + REQUEST_1_TERMS + """

                "status": "APPROVED",
                "proposedBy": "credit1",
                "proposedAt": "2026-12-15T10:20:31+02:00",
                "decidedBy": "finance1",
                "decidedAt": "2026-12-15T14:02:11+02:00",
                "decisionComment": "Recovery attempts reviewed with Credit"
              }""";

    public static final String PROPOSED = """
            {
              "code": "CREATED",
              "message": "Write-off request proposed; it applies once FINANCE or a SUPER_ADMIN approves it",
              "data": """ + REQUEST_1_HEAD + """

                "loanStatus": "DISBURSED",""" + REQUEST_1_TERMS + """

                "status": "PENDING",
                "proposedBy": "credit1",
                "proposedAt": "2026-12-15T10:20:31+02:00"
              }
            }""";

    public static final String APPROVAL = """
            {
              "decision": "APPROVED",
              "comment": "Recovery attempts reviewed with Credit"
            }""";

    public static final String APPROVED = """
            {
              "code": "OK",
              "message": "Staff loan SGL-2026-000151 written off",
              "data": """ + REQUEST_1_APPROVED + """

            }""";

    public static final String WITHDRAWN = """
            {
              "code": "OK",
              "message": "Write-off request withdrawn",
              "data": """ + REQUEST_1_HEAD + """

                "loanStatus": "DISBURSED",""" + REQUEST_1_TERMS + """

                "status": "WITHDRAWN",
                "proposedBy": "credit1",
                "proposedAt": "2026-12-15T10:20:31+02:00",
                "decidedBy": "credit1",
                "decidedAt": "2026-12-15T10:31:02+02:00"
              }
            }""";

    public static final String REQUESTS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
            """ + REQUEST_1_APPROVED + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String LOAN_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Staff loan 999 not found"
            }""";

    public static final String VOUCHER_OPEN = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2026-000143's voucher 7 can still be spent, until 2026-11-05T23:59:59+02:00; a loan whose value can still be drawn cannot be written off: wait until it expires"
            }""";

    public static final String NOT_PAID_OUT = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2026-000158 has not been paid out: cancel it instead of writing it off"
            }""";

    public static final String PENDING_EXISTS = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2026-000151 already has a write-off request waiting for a decision (1); approve, reject or withdraw it first"
            }""";

    public static final String NOTHING_TO_REVERSE = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2026-000143 is DISBURSED, not written off, so there is no write-off to reverse"
            }""";

    public static final String SECOND_OPEN_LOAN = """
            {
              "code": "CONFLICT",
              "message": "Employee E1060 holds staff loan SGL-2026-000158, which is open; putting SGL-2026-000112 back would give them two open loans. It can be reversed once SGL-2026-000158 is closed"
            }""";

    public static final String NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Write-off request 99 not found"
            }""";

    public static final String ALREADY_DECIDED = """
            {
              "code": "CONFLICT",
              "message": "Write-off request 1 is already approved"
            }""";

    public static final String OWN = """
            {
              "code": "FORBIDDEN",
              "message": "finance2 proposed write-off request 2 and cannot also approve or reject it; another FINANCE user or a SUPER_ADMIN must"
            }""";

    public static final String NOT_THE_PROPOSER = """
            {
              "code": "FORBIDDEN",
              "message": "Only credit1, who proposed write-off request 1, can withdraw it; anyone else entitled approves or rejects it"
            }""";
}
