package zw.co.innbucks.loans.web;

/**
 * Example bodies for the Staff Grocery Loan journey and its portal screens, as one story, the same one the offer,
 * notification and voucher examples tell: Chipo Banda (E1012, grade C4, Treasury) holds offer 2 for USD 300.00 from
 * run 1 of Monday 5 October, takes it in full in the SuperApp on Tuesday 6 October as loan SGL-2026-000143, due on
 * 20 November; once paid out, it is voucher 7 of the voucher examples. The Apply example is a member who held no offer
 * that week (taken on after Monday's run, say): offer 3, made on Wednesday 7 October.
 */
public final class StaffLoanApiExamples {

    private StaffLoanApiExamples() {
    }

    private static final String DRAW = """
            {
                    "minimum": 10.00,
                    "increment": 5.00,
                    "maximum": 300.00,
                    "currency": "USD"
                  }""";

    private static final String OFFER_2 = """
            {
                  "offerId": 2,
                  "amount": 300.00,
                  "currency": "USD",
                  "expiresAt": "2026-10-12T08:00:01+02:00",
                  "draw": """ + DRAW + """

                }""";

    private static final String OFFER_3_APPLIED = """
            {
                  "offerId": 3,
                  "amount": 300.00,
                  "currency": "USD",
                  "expiresAt": "2026-10-14T11:20:05+02:00",
                  "draw": """ + DRAW + """

                }""";

    private static final String LOAN_143_HEAD = """
            {
                  "reference": "SGL-2026-000143",""";

    private static final String LOAN_143_TERMS = """
                  "amount": 300.00,
                  "currency": "USD",
                  "totalRepayable": 300.00,
                  "outstandingBalance": 300.00,
                  "repaymentDate": "2026-11-20",
                  "merchantName": "GetMore Groceries",
                  "acceptedAt": "2026-10-06T09:10:41+02:00",""";

    private static final String LOAN_143_AWAITING = LOAN_143_HEAD + """

                  "status": "AWAITING_DISBURSEMENT",
                  "statusMessage": "Your loan is approved. Your voucher will be sent to you once the loan is paid out to GetMore Groceries.",
            """ + LOAN_143_TERMS + """

                  "voucher": null
                }""";

    private static final String LOAN_143_DISBURSED = LOAN_143_HEAD + """

                  "status": "DISBURSED",
                  "statusMessage": "Your voucher is ready to spend at GetMore Groceries.",
            """ + LOAN_143_TERMS + """

                  "voucher": {
                    "status": "PARTIALLY_REDEEMED",
                    "faceValue": 300.00,
                    "balance": 120.00,
                    "currency": "USD",
                    "expiresAt": "2026-11-05T23:59:59+02:00",
                    "maskedCode": "**** **** **** 8406",
                    "code": "4829 1506 7331 8406",
                    "scanValue": "4829150673318406"
                  }
                }""";

    public static final String HOME_OFFER = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "loan": null,
                "offer": """ + OFFER_2 + """
            ,
                "canApply": false,
                "unavailable": null
              }
            }""";

    public static final String HOME_APPLY = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "loan": null,
                "offer": null,
                "canApply": true,
                "unavailable": null
              }
            }""";

    public static final String HOME_LOAN = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "loan": """ + LOAN_143_AWAITING + """
            ,
                "offer": null,
                "canApply": false,
                "unavailable": null
              }
            }""";

    public static final String HOME_VOUCHER = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "loan": """ + LOAN_143_DISBURSED + """
            ,
                "offer": null,
                "canApply": false,
                "unavailable": null
              }
            }""";

    public static final String HOME_UNAVAILABLE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "loan": null,
                "offer": null,
                "canApply": false,
                "unavailable": {
                  "reason": "GRADE_NOT_ELIGIBLE",
                  "message": "Your grade does not qualify for a Staff Grocery Loan at the moment."
                }
              }
            }""";

    public static final String APPLIED = """
            {
              "code": "CREATED",
              "message": "Created",
              "data": {
                "offer": """ + OFFER_3_APPLIED + """
            ,
                "created": true
              }
            }""";

    public static final String APPLIED_HELD = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "offer": """ + OFFER_2 + """
            ,
                "created": false
              }
            }""";

    public static final String QUOTE_REQUEST = """
            {
              "offerId": 2,
              "amount": 300.00
            }""";

    public static final String AGREEMENT_CONTENT = "STAFF GROCERY LOAN AGREEMENT\\n\\nI, Chipo Banda, employee"
            + " number E1012, borrow USD 300.00 from InnBucks Microfinance Bank at 0% interest, as a voucher to spend"
            + " at GetMore Groceries within 30 days. I will repay USD 300.00, collected from my salary on 2026-11-20."
            + " If you do not spend the whole voucher before it expires, you still repay the full amount.\\n\\n"
            + "Accepted on 2026-10-06.";

    public static final String AGREEMENT_SHA256 = "f884d117b9c52ef69491a3a85ba4a7b42573127cb1847f168187bb49a9b3ad17";

    public static final String QUOTE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "offerId": 2,
                "amount": 300.00,
                "currency": "USD",
                "interestRate": 0,
                "interest": 0.00,
                "fees": 0.00,
                "totalRepayable": 300.00,
                "repaymentDate": "2026-11-20",
                "collection": "USD 300.00 will be collected automatically from your salary on 2026-11-20. There is nothing for you to pay yourself.",
                "merchantName": "GetMore Groceries",
                "redemption": "You receive a voucher for USD 300.00 to spend at GetMore Groceries only, valid for 30 days. The loan is never paid into your wallet.",
                "voucherValidityDays": 30,
                "unredeemedVoucher": "If you do not spend the whole voucher before it expires, you still repay the full amount.",
                "agreement": {
                  "version": 1,
                  "title": "Staff Grocery Loan Agreement",
                  "content": \"""" + AGREEMENT_CONTENT + """
            ",
                  "contentSha256": \"""" + AGREEMENT_SHA256 + """
            "
                }
              }
            }""";

    public static final String ACCEPT_REQUEST = """
            {
              "offerId": 2,
              "amount": 300.00,
              "agreementVersion": 1,
              "agreementSha256": \"""" + AGREEMENT_SHA256 + """
            ",
              "assertion": "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJpbm5idWNrcy1taWRkbGV3YXJlIiwiYW1yIjpbInBpbiJdfQ.c2lnbmF0dXJl"
            }""";

    public static final String ACCEPTED = """
            {
              "code": "CREATED",
              "message": "Created",
              "data": """ + LOAN_143_AWAITING + """

            }""";

    public static final String DECLINED_ACTIVE_LOAN = """
            {
              "code": "STAFF_LOAN_DECLINED",
              "message": "You already have a Staff Grocery Loan. You can take another once it has been repaid.",
              "data": {
                "reason": "HAS_ACTIVE_LOAN"
              }
            }""";

    public static final String DECLINED_OFFER = """
            {
              "code": "STAFF_LOAN_DECLINED",
              "message": "This offer is no longer available. Apply again to see what you can borrow.",
              "data": {
                "reason": "OFFER_NOT_AVAILABLE"
              }
            }""";

    public static final String DECLINED_UNAVAILABLE = """
            {
              "code": "STAFF_LOAN_DECLINED",
              "message": "Staff Grocery Loans are not available right now. Please try again later.",
              "data": {
                "reason": "TEMPORARILY_UNAVAILABLE"
              }
            }""";

    public static final String AMOUNT_NOT_ALLOWED = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "amount": "Choose an amount from USD 10.00 to USD 300.00 in steps of USD 5.00"
              }
            }""";

    public static final String DEVICE_MISSING = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "X-Device-Id": "The device accepting the loan is required: send it in the X-Device-Id header"
              }
            }""";

    public static final String TERMS_UNAVAILABLE = """
            {
              "code": "STAFF_LOAN_TERMS_UNAVAILABLE",
              "message": "Staff Grocery Loans are not available right now. Please try again later."
            }""";

    public static final String TERMS_CHANGED = """
            {
              "code": "TERMS_CHANGED",
              "message": "The loan terms have changed since you read them. Please review them again before accepting."
            }""";

    public static final String STEP_UP_REQUIRED = """
            {
              "code": "STEP_UP_REQUIRED",
              "message": "Confirm with your PIN or biometrics to accept the loan."
            }""";

    // --- The portal ---

    private static final String STAFF_LOAN_143_HEAD = """
            {
                  "id": 143,
                  "reference": "SGL-2026-000143",
                  "offerId": 2,
                  "staffMemberId": 2,
                  "employeeNumber": "E1012",
                  "fullName": "Chipo Banda",
                  "msisdn": "****6789",
                  "grade": "C4",
                  "amount": 300.00,
                  "currency": "USD",
                  "interestRate": 0.0000,
                  "totalRepayable": 300.00,
                  "dueDate": "2026-11-20",
                  "unredeemedVoucherTreatment": "DEBT_STANDS",""";

    private static final String STAFF_LOAN_143 = STAFF_LOAN_143_HEAD + """

                  "status": "AWAITING_DISBURSEMENT",
                  "inArrears": false,
                  "acceptedAt": "2026-10-06T09:10:41+02:00",
                  "disbursedAt": null,
                  "disbursementReference": null,
                  "settledAt": null,
                  "cancelledAt": null,
                  "cancelledBy": null,
                  "cancellationReason": null,
                  "employmentFlag": null
                }""";

    // Nyasha Dube (E1001) took offer 1 up as SGL-2026-000151 on 8 October; it was paid out and voucher 8 issued
    // (the voucher examples). Human Capital's register batch 21 moved her to RESIGNED on 28 October, so the loan is
    // flagged for recovery from her terminal benefits.
    private static final String STAFF_LOAN_151_FLAGGED = """
            {
                  "id": 151,
                  "reference": "SGL-2026-000151",
                  "offerId": 1,
                  "staffMemberId": 1,
                  "employeeNumber": "E1001",
                  "fullName": "Nyasha Dube",
                  "msisdn": "****6983",
                  "grade": "C4",
                  "amount": 250.00,
                  "currency": "USD",
                  "interestRate": 0.0000,
                  "totalRepayable": 250.00,
                  "dueDate": "2026-11-20",
                  "unredeemedVoucherTreatment": "DEBT_STANDS",
                  "status": "DISBURSED",
                  "inArrears": false,
                  "acceptedAt": "2026-10-08T07:58:30+02:00",
                  "disbursedAt": "2026-10-08T08:05:38+02:00",
                  "disbursementReference": "BRNET-20261008-0002",
                  "settledAt": null,
                  "cancelledAt": null,
                  "cancelledBy": null,
                  "cancellationReason": null,
                  "employmentFlag": {
                    "employmentStatus": "RESIGNED",
                    "action": "RECOVER_FROM_TERMINAL_BENEFITS",
                    "flaggedAt": "2026-10-28T10:14:03+02:00",
                    "registerBatchId": 21
                  }
                }""";

    public static final String STAFF_LOANS_FLAGGED = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  """ + STAFF_LOAN_151_FLAGGED + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_LOANS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  """ + STAFF_LOAN_143 + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_LOAN_DETAIL = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "loan": """ + STAFF_LOAN_143 + """
            ,
                "agreement": {
                  "instrumentType": "STAFF_GROCERY_LOAN_AGREEMENT",
                  "templateVersion": 1,
                  "title": "Staff Grocery Loan Agreement",
                  "content": \"""" + AGREEMENT_CONTENT + """
            ",
                  "contentSha256": \"""" + AGREEMENT_SHA256 + """
            ",
                  "acceptedBy": "borrower:E1012",
                  "acceptedAt": "2026-10-06T09:10:41+02:00",
                  "deviceId": "a1f3c9e2-7b4d-4e8a-9c21-5d6e7f8a9b0c",
                  "ipAddress": "10.0.4.17",
                  "forwardedFor": "196.27.112.45",
                  "userAgent": "InnBucks/2.4.1 (Android 14)",
                  "authenticationMethod": "pin",
                  "assertionId": "mw-7c1d9e2a",
                  "evidenceSha256": "3eb6aa62f4d351766cb2c6b70316065dfda5323f4f0e56ad1ce03d039737b512",
                  "intact": true
                }
              }
            }""";

    public static final String CANCEL_REQUEST = """
            {
              "reason": "Accepted in error: the borrower asked to cancel before payout"
            }""";

    public static final String CANCELLED = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + STAFF_LOAN_143_HEAD + """

                  "status": "CANCELLED",
                  "inArrears": false,
                  "acceptedAt": "2026-10-06T09:10:41+02:00",
                  "disbursedAt": null,
                  "disbursementReference": null,
                  "settledAt": null,
                  "cancelledAt": "2026-10-06T11:02:17+02:00",
                  "cancelledBy": "cmanager",
                  "cancellationReason": "Accepted in error: the borrower asked to cancel before payout",
                  "employmentFlag": null
                }
            }""";

    public static final String CANCEL_CONFLICT = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2026-000143 is CANCELLED; only a loan awaiting disbursement can be cancelled"
            }""";

    public static final String NOT_FOUND_143 = """
            {
              "code": "NOT_FOUND",
              "message": "Staff loan 143 not found"
            }""";

    public static final String AGREEMENT_PLACEHOLDERS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "borrowerName": "Full name on the staff register",
                "employeeNumber": "Employee number",
                "nationalIdNumber": "National ID number",
                "mobileNumber": "Mobile number the voucher is sent to",
                "department": "Department",
                "amount": "Amount borrowed, the voucher's value",
                "currency": "Currency, e.g. USD",
                "interestRate": "Interest rate, percent (0)",
                "totalRepayable": "Total to repay",
                "repaymentDate": "Date it is collected from salary, yyyy-MM-dd",
                "merchantName": "Where the voucher can be spent",
                "voucherValidityDays": "Days the voucher can be spent for",
                "unredeemedVoucherTerms": "What happens if the voucher is not spent in full before it expires",
                "acceptedDate": "Date accepted, yyyy-MM-dd"
              }
            }""";
}
