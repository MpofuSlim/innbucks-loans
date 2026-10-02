package zw.co.innbucks.loans.web;

/**
 * Example bodies for Credit's arrears overrides (FR-SGL-014), as one story: Tatenda Mhlanga (E1060) owes the balance of
 * a Staff Grocery Loan that was written off. On Tuesday 6 October credit1 proposes override 1, good until 31 October,
 * on a repayment plan Finance has agreed; credit2 approves it. On Wednesday 7 October she takes loan SGL-2026-000158 in
 * the SuperApp, which uses it up. The revocation example is the other ending: credit1 revokes it before she borrows.
 */
public final class StaffArrearsOverrideApiExamples {

    private StaffArrearsOverrideApiExamples() {
    }

    public static final String PROPOSAL = """
            {
              "employeeNumber": "E1060",
              "validUntil": "2026-10-31",
              "reason": "Repayment plan for the written-off balance agreed with Finance; payroll deduction confirmed"
            }""";

    private static final String OVERRIDE_1_HEAD = """
            {
                "id": 1,
                "employeeNumber": "E1060",
                "fullName": "Tatenda Mhlanga",
                "reason": "Repayment plan for the written-off balance agreed with Finance; payroll deduction confirmed",
                "validUntil": "2026-10-31",""";

    private static final String OVERRIDE_1_DECIDED = """

                "proposedBy": "credit1",
                "proposedAt": "2026-10-06T09:30:12+02:00",
                "decidedBy": "credit2",
                "decidedAt": "2026-10-06T10:15:40+02:00",
                "decisionComment": "Plan confirmed with Finance",""";

    public static final String PROPOSED = """
            {
              "code": "CREATED",
              "message": "Arrears override proposed; it applies once another credit manager approves it",
              "data": """ + OVERRIDE_1_HEAD + """

                "status": "PENDING",
                "proposedBy": "credit1",
                "proposedAt": "2026-10-06T09:30:12+02:00"
              }
            }""";

    public static final String APPROVAL = """
            {
              "decision": "APPROVED",
              "comment": "Plan confirmed with Finance"
            }""";

    public static final String APPROVED = """
            {
              "code": "OK",
              "message": "Arrears override approved; the employee may take one Staff Grocery Loan under it until 2026-10-31",
              "data": """ + OVERRIDE_1_HEAD + """

                "status": "APPROVED",""" + OVERRIDE_1_DECIDED + """

                "inForce": true
              }
            }""";

    public static final String WITHDRAWN = """
            {
              "code": "OK",
              "message": "Arrears override withdrawn",
              "data": """ + OVERRIDE_1_HEAD + """

                "status": "WITHDRAWN",
                "proposedBy": "credit1",
                "proposedAt": "2026-10-06T09:30:12+02:00",
                "decidedBy": "credit1",
                "decidedAt": "2026-10-06T09:41:55+02:00"
              }
            }""";

    public static final String REVOCATION = """
            {
              "reason": "Approved in error: the repayment plan is not signed yet"
            }""";

    public static final String REVOKED = """
            {
              "code": "OK",
              "message": "Arrears override revoked; the written-off balance stops new loans again",
              "data": """ + OVERRIDE_1_HEAD + """

                "status": "REVOKED",""" + OVERRIDE_1_DECIDED + """

                "revokedBy": "credit1",
                "revokedAt": "2026-10-06T15:02:11+02:00",
                "revocationReason": "Approved in error: the repayment plan is not signed yet"
              }
            }""";

    public static final String OVERRIDES = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
            """ + OVERRIDE_1_HEAD + """

                "status": "USED",""" + OVERRIDE_1_DECIDED + """

                "staffLoanReference": "SGL-2026-000158",
                "usedAt": "2026-10-07T08:41:09+02:00"
              }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String NOTHING_TO_OVERRIDE = """
            {
              "code": "CONFLICT",
              "message": "Employee E1012 owes no written-off Staff Grocery Loan, so there is nothing to override. An overdue loan cannot be overridden: it must be repaid first"
            }""";

    public static final String PENDING_EXISTS = """
            {
              "code": "CONFLICT",
              "message": "Employee E1060 already has an arrears override waiting for a decision (1); approve, reject or withdraw it first"
            }""";

    public static final String VALID_UNTIL_TOO_FAR = """
            {
              "code": "INVALID_REQUEST",
              "message": "Valid until must be at most 90 days ahead, on or before 2027-01-04"
            }""";

    public static final String NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Arrears override 99 not found"
            }""";

    public static final String ALREADY_DECIDED = """
            {
              "code": "CONFLICT",
              "message": "Arrears override 1 is already approved"
            }""";

    public static final String LAPSED = """
            {
              "code": "CONFLICT",
              "message": "Arrears override 1's last day, 2026-10-31, has passed; reject it and propose a new one"
            }""";

    public static final String OWN = """
            {
              "code": "FORBIDDEN",
              "message": "credit1 proposed arrears override 1 and cannot also approve or reject it; another credit manager or SUPER_ADMIN must"
            }""";

    public static final String NOT_THE_PROPOSER = """
            {
              "code": "FORBIDDEN",
              "message": "Only credit1, who proposed arrears override 1, can withdraw it; anyone else approves or rejects it"
            }""";

    public static final String NOTHING_TO_REVOKE = """
            {
              "code": "CONFLICT",
              "message": "Arrears override 1 is used, so there is nothing to revoke"
            }""";
}
