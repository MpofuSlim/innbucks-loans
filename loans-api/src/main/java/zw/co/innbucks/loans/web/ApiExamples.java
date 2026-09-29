package zw.co.innbucks.loans.web;

/**
 * Swagger example bodies shared across controllers. One story runs through them: agent
 * {@code tmoyo} of merchant {@code harare-motors} captures loan 42 for Rudo Chikwanha, SSB approves
 * the deduction, and credit manager {@code cmanager} approves it. Figures are what the service
 * computes with the seeded parameters (6% admin fee, 7% monthly interest, 3% commission, the
 * 80-20 commission group), so running the requests in order reproduces them.
 */
public final class ApiExamples {

    private ApiExamples() {
    }

    // --- Errors every secured endpoint can answer ---

    public static final String UNAUTHORIZED = """
            {
              "code": "UNAUTHORIZED",
              "message": "Invalid or missing token"
            }""";

    public static final String FORBIDDEN = """
            {
              "code": "FORBIDDEN",
              "message": "Forbidden - insufficient role"
            }""";

    public static final String INTERNAL_ERROR = """
            {
              "code": "INTERNAL_ERROR",
              "message": "An unexpected error occurred"
            }""";

    public static final String LOAN_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Loan 42 not found"
            }""";

    // --- Shared records ---

    public static final String COMMISSION_GROUP = """
            {
                  "id": 1,
                  "name": "80-20-Favouring-InnBucks",
                  "agentCommission": 20.0,
                  "providerCommission": 80.0,
                  "percentage": true
                }""";

    public static final String MERCHANT = """
            {
                "id": 2,
                "merchantCode": "harare-motors",
                "companyName": "Harare Motor Spares",
                "disbursementType": "MERCHANT_MOBILE_WALLET",
                "accountNumber": "0771234521",
                "commissionStructure": "MERCHANT_DEFINED",
                "commissionGroup": """ + COMMISSION_GROUP + """
            ,
                "physicalAddress": "45 Kenneth Kaunda Ave, Harare",
                "contactPersonName": "Farai Ndlovu",
                "contactPersonMobileNumber": "+263772100200",
                "contactPersonEmail": "farai@hararemotors.co.zw"
              }""";

    public static final String AGENT_USER = """
            {
                "id": 7,
                "username": "tmoyo",
                "firstName": "Tendai",
                "lastName": "Moyo",
                "email": "tendai.moyo@hararemotors.co.zw",
                "mobileNumber": "263771234000",
                "idNumber": "632345678B42",
                "temporaryPassword": true,
                "groups": ["AGENTS"],
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
                "commissionGroup": """ + COMMISSION_GROUP + """
            ,
                "physicalAddress": "12 Samora Machel Ave, Harare"
              }""";

    /** Loan 42 as a list row, once SSB has approved the deduction and before Credit decides. */
    public static final String LOAN_SUMMARY = """
            {
                  "id": 42,
                  "reference": "000000042",
                  "createdAt": "2026-09-29T10:15:30+02:00",
                  "createdBy": "tmoyo",
                  "merchantCode": "harare-motors",
                  "merchantName": "Harare Motor Spares",
                  "firstName": "Rudo",
                  "lastName": "Chikwanha",
                  "ecNumber": "1234567A",
                  "mobileNumber": "263771234567",
                  "principal": 531.91,
                  "disbursedAmount": 500.00,
                  "tenor": 3,
                  "monthlyInstallment": 202.69,
                  "ssbApprovalStatus": "APPROVED",
                  "creditApprovalStatus": "PENDING",
                  "bookingStatus": "PENDING",
                  "disbursementStatus": "PENDING"
                }""";

    public static final String LOAN_PAGE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  """ + LOAN_SUMMARY + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    private static final String LOAN_APPLICANT = """
                "firstName": "Rudo",
                "lastName": "Chikwanha",
                "dateOfBirth": "1988-04-12",
                "gender": "FEMALE",
                "title": "MRS",
                "maritalStatus": "MARRIED",
                "numberOfDependants": 3,
                "numberOfChildren": 2,
                "educationLevel": "DEGREE",
                "placeOfBirth": "Gweru",
                "profession": "Teacher",
                "mobileNumber": "263771234567",
                "walletNumber": "263771234567",
                "email": "rudo.chikwanha@example.co.zw",
                "ecNumber": "1234567A",
                "nationalIdNumber": "631234567A42",
                "address": {
                  "street": "14 Fife Ave",
                  "suburb": "Avenues",
                  "city": "Harare",
                  "country": "Zimbabwe"
                },
                "employmentDetail": {
                  "employerName": "Government of Zimbabwe",
                  "ministry": "Ministry of Primary and Secondary Education",
                  "station": "Mabelreign Girls High School",
                  "grade": "D2",
                  "contractType": "PERMANENT",
                  "employerContactNumber": "+263242734051",
                  "employeeNumber": "1234567A",
                  "grossSalary": 850.00,
                  "netSalary": 620.00,
                  "employmentStartDate": "2012-01-09",
                  "address": {
                    "street": "Ambassador House, Kwame Nkrumah Ave",
                    "city": "Harare",
                    "country": "Zimbabwe"
                  }
                },
                "payslipDeductions": [
                  {
                    "beneficiary": "ZIMRA PAYE",
                    "amount": 142.50
                  },
                  {
                    "beneficiary": "PSMAS medical aid",
                    "amount": 45.00
                  },
                  {
                    "beneficiary": "APEX pension",
                    "amount": 42.50
                  }
                ],
                "nextOfKin": {
                  "firstName": "Tatenda",
                  "lastName": "Chikwanha",
                  "nationalId": "63-7654321-C-42",
                  "mobileNumber": "+263772345678",
                  "relationship": "SPOUSE",
                  "gender": "MALE",
                  "address": {
                    "street": "14 Fife Ave",
                    "suburb": "Avenues",
                    "city": "Harare",
                    "country": "Zimbabwe"
                  }
                },
                "loanPurpose": "PERSONAL_USE",
                "lineOfBusiness": "EDUCATION",""";

    private static final String LOAN_TERMS = """
                "principal": 531.91,
                "feeRate": 6,
                "feeAmount": 31.91,
                "interestRate": 7,
                "interestAmount": 76.14,
                "disbursedAmount": 500.00,
                "tenor": 3,
                "monthlyInstallment": 202.69,
                "grossedMonthlyDeduction": 208.96,
                "commissionRate": 3,
                "commissionPercentage": true,
                "agentCommissionRate": 20.0,
                "agentCommission": 3.19,
                "repaymentStartDate": "2026-10-31",
                "repaymentEndDate": "2026-12-31",
                "ssbApprovalStatus": "APPROVED",
                "ssbStatusChangedAt": "2026-09-30T08:05:12+02:00",
                "ssbDeductionId": "88213",""";

    private static final String LOAN_DOCUMENTS = """
                "bookingStatus": "PENDING",
                "disbursementStatus": "PENDING",
                "signature": "iVBORw0KGgoAAAANSUhEUgAA...",
                "nationalIdPicture": "JVBERi0xLjQKJcfsj6IK...",
                "payslipPicture": "JVBERi0xLjQKJcfsj6IK..."
              }""";

    /** Loan 42 in full, once SSB has approved the deduction and before Credit decides. */
    public static final String LOAN_AWAITING_CREDIT = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": 42,
                "reference": "000000042",
                "createdAt": "2026-09-29T10:15:30+02:00",
                "createdBy": "tmoyo",
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

                "creditApprovalStatus": "PENDING",
            """ + LOAN_DOCUMENTS + """

            }""";

    /** Loan 42 in full after {@code cmanager} approves it. */
    public static final String LOAN_CREDIT_APPROVED = """
            {
              "code": "OK",
              "message": "Loan approved",
              "data": {
                "id": 42,
                "reference": "000000042",
                "createdAt": "2026-09-29T10:15:30+02:00",
                "createdBy": "tmoyo",
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

                "creditApprovalStatus": "APPROVED",
                "creditDecisionAt": "2026-09-30T11:40:02+02:00",
                "creditDecisionBy": "cmanager",
                "creditDecisionComment": "Payslip and deduction capacity verified",
                "creditDecisionReasonCode": "APPROVE_WITHIN_POLICY",
            """ + LOAN_DOCUMENTS + """

            }""";

    /** Loan 42 in full after {@code cmanager} returns it for more information. */
    public static final String LOAN_CREDIT_RETURNED = """
            {
              "code": "OK",
              "message": "Loan returned for more information",
              "data": {
                "id": 42,
                "reference": "000000042",
                "createdAt": "2026-09-29T10:15:30+02:00",
                "createdBy": "tmoyo",
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

                "creditApprovalStatus": "RETURNED",
                "creditDecisionAt": "2026-09-30T09:12:45+02:00",
                "creditDecisionBy": "cmanager",
                "creditDecisionComment": "Payslip is for June; confirm the August figures with the employer",
                "creditDecisionReasonCode": "RETURN_PAYSLIP",
            """ + LOAN_DOCUMENTS + """

            }""";

    /** Loan 42 in full after {@code tmoyo} answers the return: back in the credit queue. */
    public static final String LOAN_RESUBMITTED = """
            {
              "code": "OK",
              "message": "Loan resubmitted to Credit",
              "data": {
                "id": 42,
                "reference": "000000042",
                "createdAt": "2026-09-29T10:15:30+02:00",
                "createdBy": "tmoyo",
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

                "creditApprovalStatus": "PENDING",
            """ + LOAN_DOCUMENTS + """

            }""";

    /**
     * What loan 42 looked like at each credit action: nothing on it changed between them. Compact, exactly as
     * stored and returned, so {@link #LOAN_42_SNAPSHOT_SHA256} is its real SHA-256.
     */
    private static final String LOAN_42_SNAPSHOT =
            "{\"reference\":\"000000042\",\"ecNumber\":\"1234567A\",\"firstName\":\"Rudo\",\"lastName\":\"Chikwanha\","
            + "\"merchantCode\":\"harare-motors\",\"originator\":\"tmoyo\",\"ssbApprovalStatus\":\"APPROVED\",\"ssbDeductionId\":\"88213\","
            + "\"batchNumber\":\"B-20260930-1\",\"principal\":531.91,\"disbursedAmount\":500.00,\"tenor\":3,\"interestRate\":7.00,"
            + "\"feeRate\":6.00,\"monthlyInstallment\":202.69,\"grossedMonthlyDeduction\":208.96,"
            + "\"employment\":{\"employerName\":\"Government of Zimbabwe\",\"ministry\":\"Ministry of Primary and Secondary Education\","
            + "\"station\":\"Mabelreign Girls High School\",\"grade\":\"D2\",\"contractType\":\"PERMANENT\",\"dateOfEngagement\":\"2012-01-09\","
            + "\"grossSalary\":850.00,\"netSalary\":620.00},"
            + "\"payslipDeductions\":[{\"beneficiary\":\"ZIMRA PAYE\",\"amount\":142.50},{\"beneficiary\":\"PSMAS medical aid\",\"amount\":45.00},"
            + "{\"beneficiary\":\"APEX pension\",\"amount\":42.50}],"
            + "\"payoutType\":\"MERCHANT_MOBILE_WALLET\",\"payoutAccount\":\"****4521\","
            + "\"documents\":{\"payslipPictureSha256\":\"c224eb50562422c9c196652076f60c4d49c1279e113529a8fda9453b6cd3cdda\","
            + "\"nationalIdPictureSha256\":\"0321ebe1c478509c95b7c1344371a2e6d769d6ac8cc8201dc611acaa2e23474a\","
            + "\"signatureSha256\":\"1d99c003252ae63793afb48d4d46475a0ed94ab758010deea63758d3fdf9642d\"}}";

    private static final String LOAN_42_SNAPSHOT_SHA256 = "9f16ad5d7cd62a86a429e4dd9e8d2a5178c146e06ccdf7e6cd1e758a7ca7e387";

    private static final String LOAN_42_SNAPSHOT_FIELDS = "\"loanSnapshot\": " + LOAN_42_SNAPSHOT
            + ",\n      \"snapshotSha256\": \"" + LOAN_42_SNAPSHOT_SHA256 + "\"";

    /** Loan 42's credit decision log: returned, answered, then approved by someone other than the answerer. */
    public static final String CREDIT_DECISION_LOG = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "id": 17,
                  "action": "RETURNED",
                  "reasonCode": "RETURN_PAYSLIP",
                  "reasonDescription": "Payslip missing, unclear or out of date",
                  "comment": "Payslip is for June; confirm the August figures with the employer",
                  "performedBy": "cmanager",
                  "performedAt": "2026-09-30T09:12:45+02:00",
                  """ + LOAN_42_SNAPSHOT_FIELDS + """

                },
                {
                  "id": 18,
                  "action": "RESUBMITTED",
                  "comment": "Confirmed with the school bursar: the August payslip figures match the application",
                  "performedBy": "tmoyo",
                  "performedAt": "2026-09-30T10:03:10+02:00",
                  """ + LOAN_42_SNAPSHOT_FIELDS + """

                },
                {
                  "id": 19,
                  "action": "APPROVED",
                  "reasonCode": "APPROVE_WITHIN_POLICY",
                  "reasonDescription": "Meets credit policy",
                  "comment": "Payslip and deduction capacity verified",
                  "performedBy": "cmanager",
                  "performedAt": "2026-09-30T11:40:02+02:00",
                  """ + LOAN_42_SNAPSHOT_FIELDS + """

                }
              ]
            }""";

    /** Every active reason code, as seeded. */
    public static final String CREDIT_REASON_CODES = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                { "code": "APPROVE_WITHIN_POLICY", "decision": "APPROVED", "description": "Meets credit policy" },
                { "code": "APPROVE_RISK_ACCEPTED", "decision": "APPROVED", "description": "Outside a guideline, risk accepted; see comment" },
                { "code": "REJECT_AFFORDABILITY", "decision": "REJECTED", "description": "Deduction capacity insufficient" },
                { "code": "REJECT_EMPLOYMENT", "decision": "REJECTED", "description": "Employment not verified or not eligible" },
                { "code": "REJECT_IDENTITY", "decision": "REJECTED", "description": "Identity could not be verified" },
                { "code": "REJECT_DOCUMENTS", "decision": "REJECTED", "description": "Documents invalid, unreadable or inconsistent" },
                { "code": "REJECT_SUSPECTED_FRAUD", "decision": "REJECTED", "description": "Suspected fraud or misrepresentation" },
                { "code": "REJECT_CREDIT_HISTORY", "decision": "REJECTED", "description": "Adverse credit history or existing exposure" },
                { "code": "REJECT_CUSTOMER_REQUEST", "decision": "REJECTED", "description": "Withdrawn at the customer's request" },
                { "code": "REJECT_OTHER", "decision": "REJECTED", "description": "Other; see comment" },
                { "code": "RETURN_PAYSLIP", "decision": "RETURNED", "description": "Payslip missing, unclear or out of date" },
                { "code": "RETURN_IDENTITY_DOCUMENT", "decision": "RETURNED", "description": "National ID missing or unclear" },
                { "code": "RETURN_EMPLOYMENT_DETAIL", "decision": "RETURNED", "description": "Employment details incomplete or inconsistent" },
                { "code": "RETURN_APPLICANT_DETAIL", "decision": "RETURNED", "description": "Applicant details incomplete or inconsistent" },
                { "code": "RETURN_OTHER", "decision": "RETURNED", "description": "Other; see comment" }
              ]
            }""";

    /** Loan 57 held for review: Rudo Chikwanha's payslip file (loan 42) under another applicant. */
    public static final String PAYSLIP_REVIEW_QUEUE = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "loanId": 57,
                  "reference": "000000057",
                  "createdAt": "2026-10-01T09:20:11+02:00",
                  "createdBy": "tmoyo",
                  "merchantCode": "harare-motors",
                  "firstName": "Tendai",
                  "lastName": "Ncube",
                  "ecNumber": "7654321B",
                  "nationalIdNumber": "637654321B42",
                  "grossSalary": 850.00,
                  "netSalary": 620.00,
                  "flags": [
                    {
                      "reason": "PAYSLIP_REUSED_BY_ANOTHER_APPLICANT",
                      "detail": "Same payslip file as loan 000000042",
                      "matchedLoanId": 42,
                      "matchedReference": "000000042",
                      "matchedFirstName": "Rudo",
                      "matchedLastName": "Chikwanha",
                      "matchedEcNumber": "1234567A",
                      "matchedNationalIdNumber": "631234567A42",
                      "matchedCreatedAt": "2026-09-29T10:15:30+02:00",
                      "matchedSsbApprovalStatus": "APPROVED",
                      "matchedCreditApprovalStatus": "APPROVED"
                    },
                    {
                      "reason": "DEDUCTIONS_EXCEED_GROSS_LESS_NET",
                      "detail": "Deductions total 280.00 but gross less net is 230.00"
                    }
                  ]
                }
              ]
            }""";

    private static final String LOAN_57 = """
                "id": 57,
                "reference": "000000057",
                "createdAt": "2026-10-01T09:20:11+02:00",
                "createdBy": "tmoyo",
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
                "firstName": "Tendai",
                "lastName": "Ncube",
                "ecNumber": "7654321B",
                "mobileNumber": "263772345678",
                "walletNumber": "263772345678",
                "principal": 531.91,
                "disbursedAmount": 500.00,
                "tenor": 3,
                "monthlyInstallment": 202.69,
                "grossedMonthlyDeduction": 208.96,
                "ssbApprovalStatus": "NEW",""";

    /** Loan 57 after {@code cmanager} clears its payslip review: next in line for SSB lodgement. */
    public static final String LOAN_PAYSLIP_REVIEW_CLEARED = """
            {
              "code": "OK",
              "message": "Payslip review cleared; the application goes on to SSB",
              "data": {
            """ + LOAN_57 + """

                "creditApprovalStatus": "PENDING",
                "payslipReviewStatus": "CLEARED",
                "payslipReviewedBy": "cmanager",
                "payslipReviewedAt": "2026-10-01T11:02:40+02:00",
                "payslipReviewComment": "Ministry payroll confirmed the applicant at grade D2; loan 42 was scanned with the wrong payslip",
                "bookingStatus": "PENDING",
                "disbursementStatus": "PENDING"
              }
            }""";

    /** Loan 57 after {@code cmanager} confirms the suspicion: rejected before it ever reached SSB. */
    public static final String LOAN_PAYSLIP_REVIEW_CONFIRMED = """
            {
              "code": "OK",
              "message": "Suspected fraud confirmed; the application is rejected",
              "data": {
            """ + LOAN_57 + """

                "creditApprovalStatus": "REJECTED",
                "creditDecisionAt": "2026-10-01T11:02:40+02:00",
                "creditDecisionBy": "cmanager",
                "creditDecisionComment": "Payslip belongs to the applicant on loan 42; the applicant does not appear on the payroll",
                "creditDecisionReasonCode": "REJECT_SUSPECTED_FRAUD",
                "payslipReviewStatus": "CONFIRMED",
                "payslipReviewedBy": "cmanager",
                "payslipReviewedAt": "2026-10-01T11:02:40+02:00",
                "payslipReviewComment": "Payslip belongs to the applicant on loan 42; the applicant does not appear on the payroll",
                "bookingStatus": "PENDING",
                "disbursementStatus": "PENDING"
              }
            }""";
}
