package zw.co.innbucks.loans.web;

/**
 * Example bodies for the Staff Grocery Loan arrears report (FR-SGL-045), as it stands on Wednesday 2 December 2026,
 * with no grace and escalation after 30 days: Blessing Mutasa's SGL-2026-000127 is 43 days past its due date and
 * escalated to Credit; Nyasha Dube's SGL-2026-000151 (the staff loan examples) is 12 days past due, and she resigned
 * in October, so it is recovered from her terminal benefits; Tafadzwa Gumbo's SGL-2026-000088 was written off.
 */
public final class StaffLoanArrearsApiExamples {

    private StaffLoanArrearsApiExamples() {
    }

    public static final String REPORT = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "asOf": "2026-12-02",
                "generatedAt": "2026-12-02T07:00:01+02:00",
                "graceDays": 0,
                "escalationDays": 30,
                "totals": [
                  {
                    "currency": "USD",
                    "loans": 3,
                    "outstanding": 600.00,
                    "overdue": 3,
                    "inArrears": 3,
                    "escalated": 1,
                    "writtenOff": 1,
                    "writtenOffOutstanding": 200.00,
                    "employmentFlagged": 1,
                    "buckets": [
                      { "bucket": "NOT_DUE", "loans": 0, "outstanding": 0.00 },
                      { "bucket": "DAYS_1_TO_30", "loans": 1, "outstanding": 250.00 },
                      { "bucket": "DAYS_31_TO_60", "loans": 1, "outstanding": 150.00 },
                      { "bucket": "DAYS_61_TO_90", "loans": 0, "outstanding": 0.00 },
                      { "bucket": "OVER_90_DAYS", "loans": 1, "outstanding": 200.00 }
                    ]
                  }
                ],
                "lines": [
                  {
                    "staffLoanId": 88,
                    "reference": "SGL-2026-000088",
                    "employeeNumber": "E1088",
                    "fullName": "Tafadzwa Gumbo",
                    "department": "Treasury",
                    "employmentStatus": "ACTIVE",
                    "grade": "C4",
                    "msisdn": "****4471",
                    "merchantCode": "getmore-groceries",
                    "status": "WRITTEN_OFF",
                    "currency": "USD",
                    "amount": 200.00,
                    "outstanding": 200.00,
                    "disbursedAt": "2026-06-17T09:41:12+02:00",
                    "dueDate": "2026-07-20",
                    "daysPastDue": 135,
                    "bucket": "OVER_90_DAYS",
                    "inArrears": true,
                    "escalated": false,
                    "employmentFlag": null
                  },
                  {
                    "staffLoanId": 127,
                    "reference": "SGL-2026-000127",
                    "employeeNumber": "E1023",
                    "fullName": "Blessing Mutasa",
                    "department": "Retail Banking",
                    "employmentStatus": "ACTIVE",
                    "grade": "C4",
                    "msisdn": "****1290",
                    "merchantCode": "getmore-groceries",
                    "status": "DISBURSED",
                    "currency": "USD",
                    "amount": 150.00,
                    "outstanding": 150.00,
                    "disbursedAt": "2026-09-18T10:12:40+02:00",
                    "dueDate": "2026-10-20",
                    "daysPastDue": 43,
                    "bucket": "DAYS_31_TO_60",
                    "inArrears": true,
                    "escalated": true,
                    "employmentFlag": null
                  },
                  {
                    "staffLoanId": 151,
                    "reference": "SGL-2026-000151",
                    "employeeNumber": "E1001",
                    "fullName": "Nyasha Dube",
                    "department": "Credit",
                    "employmentStatus": "RESIGNED",
                    "grade": "C4",
                    "msisdn": "****6983",
                    "merchantCode": "getmore-groceries",
                    "status": "DISBURSED",
                    "currency": "USD",
                    "amount": 250.00,
                    "outstanding": 250.00,
                    "disbursedAt": "2026-10-08T08:05:38+02:00",
                    "dueDate": "2026-11-20",
                    "daysPastDue": 12,
                    "bucket": "DAYS_1_TO_30",
                    "inArrears": true,
                    "escalated": false,
                    "employmentFlag": {
                      "employmentStatus": "RESIGNED",
                      "action": "RECOVER_FROM_TERMINAL_BENEFITS",
                      "flaggedAt": "2026-10-28T10:14:03+02:00",
                      "registerBatchId": 21
                    }
                  }
                ]
              }
            }""";

    public static final String EMPTY = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "asOf": "2026-10-06",
                "generatedAt": "2026-10-06T07:00:01+02:00",
                "graceDays": 0,
                "escalationDays": 30,
                "totals": [],
                "lines": []
              }
            }""";
}
