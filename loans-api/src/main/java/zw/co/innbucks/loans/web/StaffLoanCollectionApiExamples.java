package zw.co.innbucks.loans.web;

/**
 * Example bodies for the Staff Grocery Loan payday statement, on the GetMore story ({@link GetMoreApiExamples}):
 * Rutendo Chari's SGL-2026-000164 (USD 150.00, accepted 14 October, debt stands) is due on Friday 20 November 2026.
 * Its voucher 9 was spent down to USD 60.00 (USD 100.00 sold, USD 10.00 refunded) and expired by staging's simulator
 * on 14 October. At 07:55 on payday its voucher was last read from GetMore before the cut-off, so the line is
 * provisional; the payday sync at 08:02:11 reads it again (nothing new) and the line is final: USD 150.00 to collect,
 * USD 90.00 to pay GetMore Groceries. Finance finalises it at 08:05:30. Staging's store is SIMULATED, so every answer
 * says it is not for payroll.
 */
public final class StaffLoanCollectionApiExamples {

    private StaffLoanCollectionApiExamples() {
    }

    private static final String HEAD = """
                "payday": "2026-11-20",
                "cutoff": "2026-11-20T00:00:00+02:00",
                "storeMode": "SIMULATED",
                "simulated": true,""";

    private static final String NOT_FINALISED = """

                "finalised": false,
                "finalisedAt": null,
                "finalisedBy": null,
                "finalisationReason": null,""";

    private static final String LINE_164_HEAD = """
                  {
                    "staffLoanId": 164,
                    "loanReference": "SGL-2026-000164",
                    "employeeNumber": "E1064",
                    "fullName": "Rutendo Chari",
                    "status": "DISBURSED",
                    "treatment": "DEBT_STANDS",
                    "voucherId": 9,
                    "voucherStateAtCutoff": "EXPIRED",
                    "stoppedReason": "SIMULATOR_EXPIRED",
                    "currency": "USD",
                    "faceValue": 150.00,
                    "spentNet": 90.00,
                    "refunded": 10.00,
                    "held": 0.00,
                    "openBalance": 0.00,
                    "amountToCollect": 150.00,
                    "couldRiseTo": null,""";

    /** After the payday sync: read since the cut-off, nothing open, the debt stands: final. */
    private static final String LINE_164 = LINE_164_HEAD + """

                    "final": true,
                    "flags": [],
                    "lastSyncedAt": "2026-11-20T08:02:11+02:00",
                    "simulated": true
                  }""";

    /** Before it: last read when the simulator expired the voucher, before the cut-off. */
    private static final String LINE_164_STALE = LINE_164_HEAD + """

                    "final": false,
                    "flags": ["STALE"],
                    "lastSyncedAt": "2026-10-14T10:45:12+02:00",
                    "simulated": true
                  }""";

    private static final String SETTLEMENT_HEAD = """
                "merchantSettlement": {
                  "merchantCode": "getmore-groceries",
                  "merchantName": "GetMore Groceries",
                  "cutoff": "2026-11-20T00:00:00+02:00",
                  "previousCycle": null,""";

    private static final String SETTLEMENT_TAIL = """

                  "totals": [
                    {
                      "currency": "USD",
                      "spends": 100.00,
                      "refunds": 10.00,
                      "net": 90.00
                    }
                  ],
                  "lines": [
                    {
                      "voucherId": 9,
                      "loanReference": "SGL-2026-000164",
                      "currency": "USD",
                      "spends": 100.00,
                      "refunds": 10.00,
                      "net": 90.00,
                      "late": false
                    }
                  ]
                },""";

    private static final String BODY_FINAL = """

                "lines": [""" + LINE_164 + """

                ],
                "totals": [
                  {
                    "currency": "USD",
                    "loans": 1,
                    "amountToCollect": 150.00,
                    "provisionalLoans": 0,
                    "provisionalAmount": 0.00
                  }
                ],""" + SETTLEMENT_HEAD + """

                  "staleVouchers": 0,""" + SETTLEMENT_TAIL + """

                "excluded": [],
                "lateAdjustments": [],
                "staleLines": 0,
                "openSyncIssues": 0
              }
            }""";

    /** Payday, after the payday sync: the statement payroll would collect from. */
    public static final String REPORT = """
            {
              "code": "OK",
              "message": "SIMULATED: not for payroll",
              "data": {""" + HEAD + NOT_FINALISED + BODY_FINAL;

    /** Payday at 07:55, before the payday sync: the voucher was last read before the cut-off. */
    public static final String REPORT_BEFORE_SYNC = """
            {
              "code": "OK",
              "message": "SIMULATED: not for payroll",
              "data": {""" + HEAD + NOT_FINALISED + """

                "lines": [""" + LINE_164_STALE + """

                ],
                "totals": [
                  {
                    "currency": "USD",
                    "loans": 1,
                    "amountToCollect": 150.00,
                    "provisionalLoans": 1,
                    "provisionalAmount": 150.00
                  }
                ],""" + SETTLEMENT_HEAD + """

                  "staleVouchers": 1,""" + SETTLEMENT_TAIL + """

                "excluded": [],
                "lateAdjustments": [],
                "staleLines": 1,
                "openSyncIssues": 0
              }
            }""";

    /** The payday sync, one page: voucher 9 read again, nothing new on it. */
    public static final String SYNCED = """
            {
              "code": "OK",
              "message": "SIMULATED: not for payroll. Payday 20 Nov 2026: 0 synced, 1 unchanged, 0 blocked, 0 unavailable; 0 cancelled voucher issuances cleaned up; done",
              "data": {
                "synced": 0,
                "unchanged": 1,
                "blocked": 0,
                "unavailable": 0,
                "cleanedUp": 0,
                "nextAfter": null,
                "stoppedEarly": false,
                "simulated": true
              }
            }""";

    /**
     * A first page during a GetMore outage: vouchers 9, 10 and 11 could not be read, one after another, so the page
     * stops there rather than wait out every voucher's timeouts.
     */
    public static final String SYNC_STOPPED_EARLY = """
            {
              "code": "OK",
              "message": "SIMULATED: not for payroll. Payday 20 Nov 2026: 0 synced, 0 unchanged, 0 blocked, 3 unavailable; 0 cancelled voucher issuances cleaned up; stopped early, GetMore is slow or not answering: repeat with after=11 once it answers",
              "data": {
                "synced": 0,
                "unchanged": 0,
                "blocked": 0,
                "unavailable": 3,
                "cleanedUp": 0,
                "nextAfter": 11,
                "stoppedEarly": true,
                "simulated": true
              }
            }""";

    public static final String FINALISE_REQUEST = """
            {
              "acceptNonFinal": false,
              "reason": null
            }""";

    public static final String FINALISE_ACCEPTING_REQUEST = """
            {
              "acceptNonFinal": true,
              "reason": "GetMore could not be read for voucher 9 before the payroll run; Finance accepts the amounts shown"
            }""";

    /** Finance finalised it: the lines kept as sent to payroll, the GetMore rows stamped. */
    public static final String FINALISED = """
            {
              "code": "OK",
              "message": "Collections for 20 Nov 2026 finalised: 1 loan, USD 150.00 to collect; USD 90.00 to pay GetMore Groceries (SIMULATED: not for payroll)",
              "data": {""" + HEAD + """

                "finalised": true,
                "finalisedAt": "2026-11-20T08:05:30+02:00",
                "finalisedBy": "finance1",
                "finalisationReason": null,""" + BODY_FINAL;

    public static final String NOT_A_PAYDAY = """
            {
              "code": "INVALID_REQUEST",
              "message": "2026-11-19 is not a payday: Staff Grocery Loans are collected on day 20 of each month"
            }""";

    public static final String FORMAT_INVALID = """
            {
              "code": "INVALID_REQUEST",
              "message": "format must be json or csv"
            }""";

    public static final String SYNC_LIMIT_INVALID = """
            {
              "code": "INVALID_REQUEST",
              "message": "limit must be between 1 and 200"
            }""";

    public static final String SYNC_OFF = """
            {
              "code": "GETMORE_UNAVAILABLE",
              "message": "GetMore unavailable (OFF): GetMore is OFF on this host (GETMORE_MODE): nothing can be read for payday 20 Nov 2026"
            }""";

    public static final String REASON_MISSING = """
            {
              "code": "INVALID_REQUEST",
              "message": "reason is required to finalise with acceptNonFinal"
            }""";

    public static final String REASON_TOO_LONG = """
            {
              "code": "VALIDATION_ERROR",
              "message": "Request validation failed",
              "data": {
                "reason": "reason must be at most 255 characters"
              }
            }""";

    public static final String ALREADY_FINALISED = """
            {
              "code": "CONFLICT",
              "message": "Payday 20 Nov 2026 is already finalised in the SIMULATED store"
            }""";

    public static final String CUTOFF_NOT_PASSED = """
            {
              "code": "CONFLICT",
              "message": "The cut-off has not passed yet: payday 20 Nov 2026 can be finalised from 00:00 on that day"
            }""";

    public static final String LATER_FINALISED = """
            {
              "code": "CONFLICT",
              "message": "A later payday, 20 Dec 2026, is already finalised in the SIMULATED store: 20 Nov 2026 can no longer be"
            }""";

    public static final String NOT_FINAL = """
            {
              "code": "CONFLICT",
              "message": "1 line is not final and 1 voucher is stale: sync first (POST /lending/v1/staff-loan-collections/2026-11-20/getmore-syncs) or finalise with acceptNonFinal and a reason"
            }""";

    /** Rows this finalisation was settling were found settled already: it keeps nothing. */
    public static final String SETTLED_CONCURRENTLY = """
            {
              "code": "CONFLICT",
              "message": "GetMore sales or refunds of payday 20 Nov 2026 were settled by another finalisation while this one ran; nothing was finalised, read it again"
            }""";
}
