package zw.co.innbucks.loans.web;

/**
 * Example bodies for the Staff Grocery Loan journey and its portal screens, as one story, the same one the offer,
 * notification and voucher examples tell: Chipo Banda (E1012, grade C4, Treasury) holds offer 2 for USD 300.00 from
 * run 1 of Monday 5 October, takes it in full in the SuperApp on Tuesday 6 October as loan SGL-2026-000143, due on
 * 20 November; once paid out, it is voucher 7 of the voucher examples. The Apply example is a member who held no offer
 * that week (taken on after Monday's run, say): offer 3, made on Wednesday 7 October.
 *
 * <p>The cash examples tell one later loan's story, in a cell paying Staff Grocery Loans in cash into the borrower's
 * InnBucks wallet (STAFF_LOANS_PAYOUT_METHOD=CASH, VEENGU_WALLET), under agreement version 2, which names the wallet.
 * Chipo takes offer 58 for USD 150.00 on Tuesday 2 February 2027 at 09:12:05 as SGL-2027-000245 (staff loan 245), due
 * 20 February, paid into her wallet ending 6789. Its payout is sent as the acceptance commits, and the bank refuses it
 * (HTTP 400, 09:12:07). Finance (finance1) has it put right and presses Pay out at 11:40:00: READY again, under the same
 * reference. That send times out (11:41:01): IN_DOUBT, never sent again. The status job's inquiry at 11:44:01 finds it
 * PROCESSING (BOOKED), and Finance's check-now at 11:46:30 finds it SETTLED: PAID, the loan DISBURSED. Two branches
 * show the other ways it ends: had Finance had the bank's word instead, Confirm paid at 11:46:30; had the 11:44:01
 * inquiry found nothing (responseCode 004), a SUPER_ADMIN (admin) records it never booked the next day, 3 February at
 * 14:20:00, once the bank confirms it and while its inquiry still reports it missing (last at 14:18:02), and the loan
 * is cancelled. A cash loan's payout fields are left out while empty; a voucher loan carries none of them.
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
                  "statusMessage": "You have USD 120.00 left to spend at GetMore Groceries.",
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

    // As it stands on 9 December: SGL-2026-000143 was collected from Chipo's salary on 20 November, with voucher 7
    // lapsed on 5 November at USD 120.00 unspent (owed all the same: DEBT_STANDS); on 7 December she took a new
    // offer up as SGL-2026-000212, awaiting payout.
    public static final String LOAN_HISTORY = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "reference": "SGL-2026-000212",
                    "status": "AWAITING_DISBURSEMENT",
                    "statusMessage": "Your loan is approved. Your voucher will be sent to you once the loan is paid out to GetMore Groceries.",
                    "amount": 200.00,
                    "currency": "USD",
                    "totalRepayable": 200.00,
                    "outstandingBalance": 200.00,
                    "repaymentDate": "2027-01-20",
                    "merchantName": "GetMore Groceries",
                    "acceptedAt": "2026-12-07T10:31:16+02:00",
                    "voucher": null
                  },
                  {
                    "reference": "SGL-2026-000143",
                    "status": "REPAID",
                    "statusMessage": "Repaid in full. Thank you.",
                    "amount": 300.00,
                    "currency": "USD",
                    "totalRepayable": 300.00,
                    "outstandingBalance": 0.00,
                    "repaymentDate": "2026-11-20",
                    "merchantName": "GetMore Groceries",
                    "acceptedAt": "2026-10-06T09:10:41+02:00",
                    "voucher": {
                      "status": "EXPIRED",
                      "faceValue": 300.00,
                      "balance": 120.00,
                      "currency": "USD",
                      "expiresAt": "2026-11-05T23:59:59+02:00",
                      "maskedCode": "**** **** **** 8406",
                      "code": null,
                      "scanValue": null
                    }
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 2,
                "totalPages": 1
              }
            }""";

    public static final String LOAN_HISTORY_NONE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [],
                "page": 0,
                "size": 20,
                "totalItems": 0,
                "totalPages": 0
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
                  "merchantCode": "getmore-groceries",
                  "merchantName": "GetMore Groceries",
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
                  "employmentFlag": null,
                  "arrearsOverrideId": null
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
                  "merchantCode": "getmore-groceries",
                  "merchantName": "GetMore Groceries",
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
                  },
                  "arrearsOverrideId": null
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
                  "employmentFlag": null,
                  "arrearsOverrideId": null
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
                "mobileNumber": "Mobile number on the staff register",
                "department": "Department",
                "amount": "Amount borrowed",
                "currency": "Currency, e.g. USD",
                "interestRate": "Interest rate, percent (0)",
                "totalRepayable": "Total to repay",
                "repaymentDate": "Date it is collected from salary, yyyy-MM-dd",
                "merchantName": "Where the voucher can be spent",
                "voucherValidityDays": "Days the voucher can be spent for",
                "unredeemedVoucherTerms": "What happens if the voucher is not spent in full before it expires",
                "payoutAccount": "Where a cash loan is paid, by its last four digits, e.g. InnBucks wallet ****3123 (blank for a voucher loan)",
                "acceptedDate": "Date accepted, yyyy-MM-dd"
              }
            }""";

    // --- A loan paid in cash: SGL-2027-000245 (see the class comment) ---

    public static final String CASH_AGREEMENT_CONTENT = "STAFF GROCERY LOAN AGREEMENT\\n\\nI, Chipo Banda, employee"
            + " number E1012, borrow USD 150.00 from InnBucks Microfinance Bank at 0% interest, paid into my InnBucks"
            + " wallet ****6789. I will repay USD 150.00, collected from my salary on 2027-02-20.\\n\\n"
            + "Accepted on 2027-02-02.";

    public static final String CASH_AGREEMENT_SHA256 =
            "6c53c4c02fd12786b5a5c5e8993a3ed73a563efc88473418dfe1a58e35342220";

    private static final String LOAN_245_HEAD = """
            {
                  "reference": "SGL-2027-000245",""";

    private static final String LOAN_245_TERMS = """
                  "amount": 150.00,
                  "currency": "USD",
                  "totalRepayable": 150.00,
                  "outstandingBalance": 150.00,
                  "repaymentDate": "2027-02-20",
                  "merchantName": null,
                  "acceptedAt": "2027-02-02T09:12:05+02:00",
                  "voucher": null,
                  "payoutMethod": "CASH",
                  "payoutAccount": "****6789"
                }""";

    private static final String LOAN_245_AWAITING = LOAN_245_HEAD + """

                  "status": "AWAITING_DISBURSEMENT",
                  "statusMessage": "Your loan is approved. USD 150.00 is being paid into your InnBucks wallet ending 6789.",
            """ + LOAN_245_TERMS;

    private static final String LOAN_245_DISBURSED = LOAN_245_HEAD + """

                  "status": "DISBURSED",
                  "statusMessage": "USD 150.00 was paid into your InnBucks wallet ending 6789. USD 150.00 will be collected from your salary on 20 Feb 2027.",
            """ + LOAN_245_TERMS;

    public static final String HOME_CASH_AWAITING = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "loan": """ + LOAN_245_AWAITING + """
            ,
                "offer": null,
                "canApply": false,
                "unavailable": null
              }
            }""";

    public static final String HOME_CASH_PAID = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "loan": """ + LOAN_245_DISBURSED + """
            ,
                "offer": null,
                "canApply": false,
                "unavailable": null
              }
            }""";

    public static final String QUOTE_CASH = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "offerId": 58,
                "amount": 150.00,
                "currency": "USD",
                "interestRate": 0,
                "interest": 0.00,
                "fees": 0.00,
                "totalRepayable": 150.00,
                "repaymentDate": "2027-02-20",
                "collection": "USD 150.00 will be collected automatically from your salary on 2027-02-20. There is nothing for you to pay yourself.",
                "merchantName": null,
                "redemption": "Once you accept, USD 150.00 is paid into your InnBucks wallet ending 6789.",
                "voucherValidityDays": null,
                "unredeemedVoucher": null,
                "agreement": {
                  "version": 2,
                  "title": "Staff Grocery Loan Agreement",
                  "content": \"""" + CASH_AGREEMENT_CONTENT + """
            ",
                  "contentSha256": \"""" + CASH_AGREEMENT_SHA256 + """
            "
                },
                "payoutMethod": "CASH",
                "payoutAccount": "****6789"
              }
            }""";

    public static final String ACCEPTED_CASH = """
            {
              "code": "CREATED",
              "message": "Created",
              "data": """ + LOAN_245_AWAITING + """

            }""";

    // The portal's view of the same loan, at each step.

    private static final String STAFF_LOAN_245_HEAD = """
            {
                  "id": 245,
                  "reference": "SGL-2027-000245",
                  "offerId": 58,
                  "staffMemberId": 2,
                  "employeeNumber": "E1012",
                  "fullName": "Chipo Banda",
                  "msisdn": "****6789",
                  "grade": "C4",
                  "merchantCode": "getmore-groceries",
                  "merchantName": "GetMore Groceries",
                  "amount": 150.00,
                  "currency": "USD",
                  "interestRate": 0.0000,
                  "totalRepayable": 150.00,
                  "dueDate": "2027-02-20",
                  "unredeemedVoucherTreatment": "DEBT_STANDS",""";

    private static final String STAFF_LOAN_245_CASH = """

                  "employmentFlag": null,
                  "arrearsOverrideId": null,
                  "payoutMethod": "CASH",
                  "payoutDestination": "VEENGU_WALLET",
                  "payoutAccount": "****6789",""";

    private static final String STAFF_LOAN_245_AWAITING = STAFF_LOAN_245_HEAD + """

                  "status": "AWAITING_DISBURSEMENT",
                  "inArrears": false,
                  "acceptedAt": "2027-02-02T09:12:05+02:00",
                  "disbursedAt": null,
                  "disbursementReference": null,
                  "settledAt": null,
                  "cancelledAt": null,
                  "cancelledBy": null,
                  "cancellationReason": null,""" + STAFF_LOAN_245_CASH;

    private static final String REFUSED_ANSWER =
            "{\\\"responseCode\\\":\\\"400\\\",\\\"responseDescription\\\":\\\"Product STAFFGROC is not set up for"
                    + " pre-approved loans\\\"}";

    private static final String BOOKED_ANSWER = "{\\\"responseCode\\\":\\\"000\\\",\\\"responseDescription\\\":"
            + "\\\"Approved or completed successfully\\\",\\\"participantReference\\\":\\\"SGL-2027-000245\\\","
            + "\\\"additionalData\\\":{\\\"loanDetails\\\":{\\\"status\\\":\\\"PROCESSING\\\","
            + "\\\"participantReference\\\":\\\"SGL-2027-000245\\\",\\\"amount\\\":15000,\\\"interestRate\\\":0.0,"
            + "\\\"msisdn\\\":\\\"****6789\\\",\\\"loanAccountNumber\\\":\\\"***\\\"}}}";

    private static final String SETTLED_ANSWER = "{\\\"responseCode\\\":\\\"000\\\",\\\"responseDescription\\\":"
            + "\\\"Approved or completed successfully\\\",\\\"participantReference\\\":\\\"SGL-2027-000245\\\","
            + "\\\"additionalData\\\":{\\\"loanDetails\\\":{\\\"status\\\":\\\"SETTLED\\\","
            + "\\\"participantReference\\\":\\\"SGL-2027-000245\\\",\\\"amount\\\":15000,\\\"interestRate\\\":0.0,"
            + "\\\"msisdn\\\":\\\"****6789\\\",\\\"loanAccountNumber\\\":\\\"***\\\"}}}";

    private static final String NOT_FOUND_ANSWER =
            "{\\\"responseCode\\\":\\\"004\\\",\\\"responseDescription\\\":\\\"No loan found\\\"}";

    /** What the 11:41:01 send came to: no answer, kept as the booking's answer. */
    private static final String TIMED_OUT = "ResourceAccessException: Read timed out";

    // 09:12:07: the bank refused the first send.
    private static final String STAFF_LOAN_245_REFUSED = STAFF_LOAN_245_AWAITING + """

                  "payoutStatus": "REFUSED",
                  "payoutMessage": "The bank refused it (HTTP 400), so nothing was booked. Send it again with Pay out once what the bank said is put right, or Credit cancels the loan.",
                  "payoutApplyAnswer": \"""" + REFUSED_ANSWER + """
            ",
                  "payoutLastAnswer": \"""" + REFUSED_ANSWER + """
            "
                }""";

    // 11:40:00: Finance pressed Pay out; READY again, the bank's refusal kept as evidence.
    private static final String STAFF_LOAN_245_READY_AGAIN = STAFF_LOAN_245_AWAITING + """

                  "payoutStatus": "READY",
                  "payoutApplyAnswer": \"""" + REFUSED_ANSWER + """
            ",
                  "payoutLastAnswer": \"""" + REFUSED_ANSWER + """
            "
                }""";

    // The branch where the bank never booked it: at 11:41:01 the send timed out, and at 11:44:01 the inquiry found no
    // loan under the reference.
    private static final String STAFF_LOAN_245_IN_DOUBT = STAFF_LOAN_245_AWAITING + """

                  "payoutStatus": "IN_DOUBT",
                  "payoutMessage": "The bank's inquiry reports no loan under SGL-2027-000245, so it may never have been booked. Ask the bank; from 2027-02-03T11:44:01+02:00 a SUPER_ADMIN may record it as not booked if the bank confirms it.",
                  "payoutNotFoundAt": "2027-02-02T11:44:01+02:00",
                  "payoutCheckedAt": "2027-02-02T11:44:01+02:00",
                  "payoutApplyAnswer": \"""" + TIMED_OUT + """
            ",
                  "payoutLastAnswer": \"""" + NOT_FOUND_ANSWER + """
            "
                }""";

    private static final String STAFF_LOAN_245_DISBURSED = STAFF_LOAN_245_HEAD + """

                  "status": "DISBURSED",
                  "inArrears": false,
                  "acceptedAt": "2027-02-02T09:12:05+02:00",
                  "disbursedAt": "2027-02-02T11:46:30+02:00",
                  "disbursementReference": "SGL-2027-000245",
                  "settledAt": null,
                  "cancelledAt": null,
                  "cancelledBy": null,
                  "cancellationReason": null,""" + STAFF_LOAN_245_CASH;

    // 11:46:30: Finance's check-now found it SETTLED (the 11:44:01 inquiry had found it PROCESSING).
    private static final String STAFF_LOAN_245_PAID = STAFF_LOAN_245_DISBURSED + """

                  "payoutStatus": "PAID",
                  "payoutBankStatus": "SETTLED",
                  "payoutBankSeenAt": "2027-02-02T11:44:01+02:00",
                  "payoutCheckedAt": "2027-02-02T11:46:30+02:00",
                  "payoutApplyAnswer": \"""" + TIMED_OUT + """
            ",
                  "payoutLastAnswer": \"""" + SETTLED_ANSWER + """
            ",
                  "bankLoanAccount": "****0245"
                }""";

    // The branch where Finance had the bank's word instead: Confirm paid at 11:46:30, over the 11:44:01 answer.
    private static final String STAFF_LOAN_245_CONFIRMED_PAID = STAFF_LOAN_245_DISBURSED + """

                  "payoutStatus": "PAID",
                  "payoutBankStatus": "PROCESSING",
                  "payoutBankSeenAt": "2027-02-02T11:44:01+02:00",
                  "payoutCheckedAt": "2027-02-02T11:44:01+02:00",
                  "payoutApplyAnswer": \"""" + TIMED_OUT + """
            ",
                  "payoutLastAnswer": \"""" + BOOKED_ANSWER + """
            "
                }""";

    // The branch where the bank never booked it: recorded so by a SUPER_ADMIN on 3 February at 14:20:00, two minutes
    // after the status job's inquiry last reported it missing.
    private static final String STAFF_LOAN_245_NOT_BOOKED = STAFF_LOAN_245_HEAD + """

                  "status": "CANCELLED",
                  "inArrears": false,
                  "acceptedAt": "2027-02-02T09:12:05+02:00",
                  "disbursedAt": null,
                  "disbursementReference": null,
                  "settledAt": null,
                  "cancelledAt": "2027-02-03T14:20:00+02:00",
                  "cancelledBy": "admin",
                  "cancellationReason": "The bank confirmed this loan's payout was never booked (recorded by admin)",""" + STAFF_LOAN_245_CASH + """

                  "payoutStatus": "FAILED",
                  "payoutMessage": "The bank confirmed this loan's payout was never booked (recorded by admin)",
                  "payoutNotFoundAt": "2027-02-02T11:44:01+02:00",
                  "payoutCheckedAt": "2027-02-03T14:18:02+02:00",
                  "payoutApplyAnswer": \"""" + TIMED_OUT + """
            ",
                  "payoutLastAnswer": \"""" + NOT_FOUND_ANSWER + """
            ",
                  "payoutFailedAt": "2027-02-03T14:20:00+02:00"
                }""";

    public static final String STAFF_LOANS_REFUSED = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  """ + STAFF_LOAN_245_REFUSED + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_LOANS_IN_DOUBT = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  """ + STAFF_LOAN_245_IN_DOUBT + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String PAYOUT_REQUESTED = """
            {
              "code": "ACCEPTED",
              "message": "Payout of SGL-2027-000245 queued",
              "data": """ + STAFF_LOAN_245_READY_AGAIN + """

            }""";

    public static final String PAYOUTS_REQUESTED = """
            {
              "code": "ACCEPTED",
              "message": "Paying 3 ready payouts; 1 you are a party to is left for someone else",
              "data": {
                "ready": 3,
                "skippedOwn": 1
              }
            }""";

    public static final String PAYOUT_CHECKED = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + STAFF_LOAN_245_PAID + """

            }""";

    public static final String PAYOUT_PAID_REQUEST = """
            {
              "bankReference": "IBK-PAY-77120",
              "note": "InnBucks operations (T. Ncube) confirmed USD 150.00 reached wallet ****6789 on 2 February"
            }""";

    public static final String PAYOUT_CONFIRMED_PAID = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + STAFF_LOAN_245_CONFIRMED_PAID + """

            }""";

    public static final String PAYOUT_NOT_BOOKED_REQUEST = """
            {
              "note": "InnBucks operations (T. Ncube) confirmed no loan was booked under SGL-2027-000245; ticket IB-8812"
            }""";

    public static final String PAYOUT_NOT_BOOKED = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + STAFF_LOAN_245_NOT_BOOKED + """

            }""";

    public static final String NOT_FOUND_245 = """
            {
              "code": "NOT_FOUND",
              "message": "Staff loan 245 not found"
            }""";

    public static final String PAYOUT_SEND_CONFLICT = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2027-000245 has payout IN_DOUBT (loan AWAITING_DISBURSEMENT); only a payout READY to send, or REFUSED by the bank, can be sent. The bank took it or may have taken it, so it is never sent again: check its status instead"
            }""";

    public static final String PAYOUT_SEND_PARTY = """
            {
              "code": "FORBIDDEN",
              "message": "cbanda is a party to staff loan SGL-2027-000245 and cannot send its payout; someone else must"
            }""";

    public static final String PAYOUT_QUEUE_FULL = """
            {
              "code": "PAYOUT_QUEUE_FULL",
              "message": "The payout queue is full, so staff loan SGL-2027-000245 was not queued; it is READY, and nothing was sent. Try again in a few minutes."
            }""";

    public static final String PAYOUTS_QUEUE_FULL = """
            {
              "code": "PAYOUT_QUEUE_FULL",
              "message": "The payout queue is full, so the 3 ready payout(s) were not queued; they are READY, and nothing was sent. Try again in a few minutes."
            }""";

    public static final String PAYOUT_CHECK_SENDING = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2027-000245 is being sent to the bank right now (since 2027-02-02T11:40:01+02:00); its status can be checked once that send is settled, or from 2027-02-02T12:10:01+02:00 if it never is"
            }""";

    public static final String PAYOUT_CHECK_NOT_CHECKABLE = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2027-000245 has payout REFUSED (loan AWAITING_DISBURSEMENT); only a payout the bank took (BOOKED) or may have taken (IN_DOUBT), or one recorded as never booked in the last 14 days, can be checked"
            }""";

    public static final String PAYOUT_CHECK_UNANSWERED = """
            {
              "code": "BANK_UNANSWERED",
              "message": "The bank's inquiry about staff loan SGL-2027-000245 gave no answer to go on (UNANSWERED); nothing changed. Try again later."
            }""";

    public static final String PAYOUT_CHECK_NOT_RECORDED = """
            {
              "code": "PAYOUT_NOT_RECORDED",
              "message": "What the bank's inquiry said about staff loan SGL-2027-000245 could not be saved; nothing changed. Try again in a minute."
            }""";

    public static final String PAYOUT_PAID_INVALID = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "bankReference": "bankReference is required"
              }
            }""";

    public static final String PAYOUT_PAID_PARTY = """
            {
              "code": "FORBIDDEN",
              "message": "cbanda is a party to staff loan SGL-2027-000245 and cannot confirm its payout paid; someone else must"
            }""";

    public static final String PAYOUT_PAID_CONFLICT = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2027-000245 has payout REFUSED (loan AWAITING_DISBURSEMENT); only a payout the bank took (BOOKED) or may have taken (IN_DOUBT) can be confirmed paid"
            }""";

    public static final String PAYOUT_NOT_BOOKED_INVALID = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "note": "note is required"
              }
            }""";

    public static final String PAYOUT_NOT_BOOKED_PARTY = """
            {
              "code": "FORBIDDEN",
              "message": "cbanda is a party to staff loan SGL-2027-000245 and cannot record its payout as never booked; someone else must"
            }""";

    public static final String PAYOUT_NOT_BOOKED_SEEN = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2027-000245 cannot be recorded as never booked: the bank has said it holds it (since 2027-02-02T11:44:01+02:00)"
            }""";

    public static final String PAYOUT_NOT_BOOKED_TOO_SOON = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2027-000245 cannot be recorded as never booked: the bank's inquiry has reported it missing only since 2027-02-02T11:44:01+02:00, and a booking may still be on its way; it can be from 2027-02-03T11:44:01+02:00"
            }""";

    public static final String PAYOUT_NOT_BOOKED_STALE = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2027-000245 cannot be recorded as never booked: the bank's inquiry last answered at 2027-02-03T13:02:44+02:00, so it may have changed since; check its status now, then record it within 6 minutes"
            }""";

    public static final String CANCEL_CONFLICT_PAYOUT = """
            {
              "code": "CONFLICT",
              "message": "Staff loan SGL-2027-000245 is being paid out (payout IN_DOUBT): the bank may already have paid it, so it can no longer be cancelled. Its status check settles it; if the bank confirms it was never booked, a SUPER_ADMIN records that with POST /lending/v1/staff-loans/245/payout/not-booked"
            }""";
}
