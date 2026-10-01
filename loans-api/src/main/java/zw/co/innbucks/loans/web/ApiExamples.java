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

    /** Any call but PUT /me/password, by a session signed in on a temporary password. */
    public static final String PASSWORD_CHANGE_REQUIRED = """
            {
              "code": "PASSWORD_CHANGE_REQUIRED",
              "message": "Change your temporary password before continuing"
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

    /**
     * Loan 42's wait for its first credit decision, seen at 09:03 on the 30th: it reached Credit when SSB approved it at
     * 08:05, and cmanager took it at 08:47; against the seeded 24-hour target and 48-hour escalation point.
     */
    private static final String LOAN_42_TURNAROUND_BEFORE_RETURN = """
            {
                    "queueEnteredAt": "2026-09-30T08:05:12+02:00",
                    "dueAt": "2026-10-01T08:05:12+02:00",
                    "escalatesAt": "2026-10-02T08:05:12+02:00",
                    "waitingHours": 1.0,
                    "overdue": false,
                    "assignedTo": "cmanager"
                  }""";

    /** Loan 42 as a list row, once SSB has approved the deduction and before Credit decides. */
    public static final String LOAN_SUMMARY = """
            {
                  "id": 42,
                  "reference": "000000042",
                  "createdAt": "2026-09-29T10:15:30+02:00",
                  "createdBy": "tmoyo",
                  "createdByName": "Tendai Moyo",
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
                  "stage": "WITH_CREDIT",
                  "creditTurnaround": """ + LOAN_42_TURNAROUND_BEFORE_RETURN + """
            ,
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

    private static final String LOAN_PAYOUT_STATUSES = """
                "bookingStatus": "PENDING",
                "disbursementStatus": "PENDING"
              }""";

    /** Loan 42's payslip as captured with the application: the June one Credit returned. */
    private static final String PAYSLIP_V1 = """
                  "documentType": "PAYSLIP",
                  "version": 1,
                  "origin": "APPLICATION",
                  "contentType": "application/pdf",
                  "sizeBytes": 248117,
                  "sha256": "c224eb50562422c9c196652076f60c4d49c1279e113529a8fda9453b6cd3cdda",
                  "uploadedBy": "tmoyo",
                  "uploadedAt": "2026-09-29T10:15:30+02:00\"""";

    /** Loan 42's replacement payslip, uploaded after Credit returned the loan. */
    private static final String PAYSLIP_V2 = """
                  "documentType": "PAYSLIP",
                  "version": 2,
                  "origin": "AMENDMENT",
                  "contentType": "application/pdf",
                  "sizeBytes": 231402,
                  "sha256": "cd437272b86852458225d27f2d07d1863b71acd4c7a4e1592c066943b14d9a3a",
                  "reason": "August payslip, as Credit asked; the June one was out of date",
                  "uploadedBy": "tmoyo",
                  "uploadedAt": "2026-09-30T09:58:20+02:00\"""";

    /** Loan 42's other documents, each still at the version captured with the application. */
    private static final String LOAN_42_OTHER_DOCUMENTS = """
                {
                  "documentType": "NATIONAL_ID",
                  "version": 1,
                  "origin": "APPLICATION",
                  "contentType": "image/jpeg",
                  "sizeBytes": 412903,
                  "sha256": "0321ebe1c478509c95b7c1344371a2e6d769d6ac8cc8201dc611acaa2e23474a",
                  "uploadedBy": "tmoyo",
                  "uploadedAt": "2026-09-29T10:15:30+02:00"
                },
                {
                  "documentType": "SIGNATURE",
                  "version": 1,
                  "origin": "APPLICATION",
                  "contentType": "image/png",
                  "sizeBytes": 6214,
                  "sha256": "1d99c003252ae63793afb48d4d46475a0ed94ab758010deea63758d3fdf9642d",
                  "uploadedBy": "tmoyo",
                  "uploadedAt": "2026-09-29T10:15:30+02:00"
                },
                {
                  "documentType": "WITNESS_SIGNATURE",
                  "version": 1,
                  "origin": "APPLICATION",
                  "contentType": "image/png",
                  "sizeBytes": 5890,
                  "sha256": "ef5f6d8b512941be0a62f9681f41e3204108d5f2d232f9ad6ce505f25f97806d",
                  "uploadedBy": "tmoyo",
                  "uploadedAt": "2026-09-29T10:15:30+02:00"
                }""";

    /**
     * Loan 42 in full, once SSB has approved the deduction and before Credit decides: each document listed at its
     * current version, without content.
     */
    public static final String LOAN_AWAITING_CREDIT = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": 42,
                "reference": "000000042",
                "createdAt": "2026-09-29T10:15:30+02:00",
                "createdBy": "tmoyo",
                "createdByName": "Tendai Moyo",
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

                "stage": "WITH_CREDIT",
                "creditTurnaround": """ + LOAN_42_TURNAROUND_BEFORE_RETURN + """
            ,
                "creditApprovalStatus": "PENDING",
                "bookingStatus": "PENDING",
                "disbursementStatus": "PENDING",
                "documents": [
                {
            """ + PAYSLIP_V1 + """

                },
            """ + LOAN_42_OTHER_DOCUMENTS + """

                ]
              }
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
                "createdByName": "Tendai Moyo",
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

                "stage": "APPROVED",
                "creditApprovalStatus": "APPROVED",
                "creditDecisionAt": "2026-09-30T11:40:02+02:00",
                "creditDecisionBy": "cmanager",
                "creditDecisionComment": "Payslip and deduction capacity verified",
                "creditDecisionReasonCode": "APPROVE_WITHIN_POLICY",
            """ + LOAN_PAYOUT_STATUSES + """

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
                "createdByName": "Tendai Moyo",
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

                "stage": "MORE_INFORMATION_NEEDED",
                "creditApprovalStatus": "RETURNED",
                "creditDecisionAt": "2026-09-30T09:12:45+02:00",
                "creditDecisionBy": "cmanager",
                "creditDecisionComment": "Payslip is for June; confirm the August figures with the employer",
                "creditDecisionReasonCode": "RETURN_PAYSLIP",
            """ + LOAN_PAYOUT_STATUSES + """

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
                "createdByName": "Tendai Moyo",
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

                "stage": "WITH_CREDIT",
                "creditApprovalStatus": "PENDING",
            """ + LOAN_PAYOUT_STATUSES + """

            }""";

    /**
     * What loan 42 looked like at each credit action, exactly as stored and returned (compact), so each
     * {@code _SHA256} below is its real SHA-256. Only the payslip changed between them: Credit returned the loan
     * on the June payslip, and it was resubmitted and approved on the August one, version 2.
     */
    private static final String LOAN_42_SNAPSHOT_TERMS =
            "{\"reference\":\"000000042\",\"ecNumber\":\"1234567A\",\"firstName\":\"Rudo\",\"lastName\":\"Chikwanha\","
            + "\"merchantCode\":\"harare-motors\",\"originator\":\"tmoyo\",\"ssbApprovalStatus\":\"APPROVED\",\"ssbDeductionId\":\"88213\","
            + "\"batchNumber\":\"B-20260930-1\",\"principal\":531.91,\"disbursedAmount\":500.00,\"tenor\":3,\"interestRate\":7.00,"
            + "\"feeRate\":6.00,\"monthlyInstallment\":202.69,\"grossedMonthlyDeduction\":208.96,"
            + "\"employment\":{\"employerName\":\"Government of Zimbabwe\",\"ministry\":\"Ministry of Primary and Secondary Education\","
            + "\"station\":\"Mabelreign Girls High School\",\"grade\":\"D2\",\"contractType\":\"PERMANENT\",\"dateOfEngagement\":\"2012-01-09\","
            + "\"grossSalary\":850.00,\"netSalary\":620.00},"
            + "\"payslipDeductions\":[{\"beneficiary\":\"ZIMRA PAYE\",\"amount\":142.50},{\"beneficiary\":\"PSMAS medical aid\",\"amount\":45.00},"
            + "{\"beneficiary\":\"APEX pension\",\"amount\":42.50}],"
            + "\"payoutType\":\"MERCHANT_MOBILE_WALLET\",\"payoutAccount\":\"****4521\",";

    private static final String LOAN_42_SNAPSHOT_JUNE_PAYSLIP = LOAN_42_SNAPSHOT_TERMS
            + "\"documents\":{\"payslipPictureSha256\":\"c224eb50562422c9c196652076f60c4d49c1279e113529a8fda9453b6cd3cdda\","
            + "\"nationalIdPictureSha256\":\"0321ebe1c478509c95b7c1344371a2e6d769d6ac8cc8201dc611acaa2e23474a\","
            + "\"signatureSha256\":\"1d99c003252ae63793afb48d4d46475a0ed94ab758010deea63758d3fdf9642d\","
            + "\"witnessSignatureSha256\":\"ef5f6d8b512941be0a62f9681f41e3204108d5f2d232f9ad6ce505f25f97806d\"}}";

    private static final String LOAN_42_SNAPSHOT_AUGUST_PAYSLIP = LOAN_42_SNAPSHOT_TERMS
            + "\"documents\":{\"payslipPictureSha256\":\"cd437272b86852458225d27f2d07d1863b71acd4c7a4e1592c066943b14d9a3a\","
            + "\"nationalIdPictureSha256\":\"0321ebe1c478509c95b7c1344371a2e6d769d6ac8cc8201dc611acaa2e23474a\","
            + "\"signatureSha256\":\"1d99c003252ae63793afb48d4d46475a0ed94ab758010deea63758d3fdf9642d\","
            + "\"witnessSignatureSha256\":\"ef5f6d8b512941be0a62f9681f41e3204108d5f2d232f9ad6ce505f25f97806d\"}}";

    private static final String LOAN_42_SNAPSHOT_JUNE_PAYSLIP_FIELDS = "\"loanSnapshot\": " + LOAN_42_SNAPSHOT_JUNE_PAYSLIP
            + ",\n      \"snapshotSha256\": \"1e195f6cc5a683ab81402e2b376b6a48c2278ddd474d0e4dfb8c1afa4a165160\"";

    private static final String LOAN_42_SNAPSHOT_AUGUST_PAYSLIP_FIELDS = "\"loanSnapshot\": " + LOAN_42_SNAPSHOT_AUGUST_PAYSLIP
            + ",\n      \"snapshotSha256\": \"ecd24a74a3694e201e6774f92e46887d98adc875f9f3b6a6f3253a04702ddf8e\"";

    /** Loan 42 returned on the June payslip. */
    private static final String CREDIT_DECISION_17 = """
                {
                  "id": 17,
                  "action": "RETURNED",
                  "reasonCode": "RETURN_PAYSLIP",
                  "reasonDescription": "Payslip missing, unclear or out of date",
                  "comment": "Payslip is for June; confirm the August figures with the employer",
                  "performedBy": "cmanager",
                  "performedAt": "2026-09-30T09:12:45+02:00",
                  """ + LOAN_42_SNAPSHOT_JUNE_PAYSLIP_FIELDS + """

                }""";

    /** Loan 42's return answered with the August payslip. */
    private static final String CREDIT_DECISION_18 = """
                {
                  "id": 18,
                  "action": "RESUBMITTED",
                  "comment": "Confirmed with the school bursar: the August payslip figures match the application",
                  "performedBy": "tmoyo",
                  "performedAt": "2026-09-30T10:03:10+02:00",
                  """ + LOAN_42_SNAPSHOT_AUGUST_PAYSLIP_FIELDS + """

                }""";

    /**
     * Loan 42's credit decision log: returned on the June payslip, answered with the August one, then approved by
     * someone other than the answerer. Each entry pins the payslip it was taken on by its fingerprint.
     */
    public static final String CREDIT_DECISION_LOG = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
            """ + CREDIT_DECISION_17 + """
            ,
            """ + CREDIT_DECISION_18 + """
            ,
                {
                  "id": 19,
                  "action": "APPROVED",
                  "reasonCode": "APPROVE_WITHIN_POLICY",
                  "reasonDescription": "Meets credit policy",
                  "comment": "Payslip and deduction capacity verified",
                  "performedBy": "cmanager",
                  "performedAt": "2026-09-30T11:40:02+02:00",
                  """ + LOAN_42_SNAPSHOT_AUGUST_PAYSLIP_FIELDS + """

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
                "createdByName": "Tendai Moyo",
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

                "stage": "RECEIVED",
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

                "stage": "DECLINED",
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

    /** Every version of loan 42's documents, without content: the June payslip is kept beside the August one. */
    public static final String LOAN_42_DOCUMENT_HISTORY = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
            """ + PAYSLIP_V1 + """

                },
                {
            """ + PAYSLIP_V2 + """

                },
            """ + LOAN_42_OTHER_DOCUMENTS + """

              ]
            }""";

    /** Loan 42's current payslip, version 2, with its content (base64, shortened here). */
    public static final String LOAN_42_PAYSLIP_CURRENT = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
            """ + PAYSLIP_V2 + """
            ,
                "content": "JVBERi0xLjcKJcfsj6IKNSAwIG9iago8PC9MZW5ndGggNiAwIFI..."
              }
            }""";

    /** Loan 42's first payslip, still on file after it was replaced. */
    public static final String LOAN_42_PAYSLIP_VERSION_1 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
            """ + PAYSLIP_V1 + """
            ,
                "content": "JVBERi0xLjQKJeLjz9MKMSAwIG9iago8PC9UeXBlIC9DYXRhbG9n..."
              }
            }""";

    /** The August payslip replacing the June one on loan 42. */
    public static final String LOAN_42_PAYSLIP_REPLACED = """
            {
              "code": "OK",
              "message": "PAYSLIP replaced; version 2 is now current",
              "data": {
            """ + PAYSLIP_V2 + """

              }
            }""";

    /** Every view and upload of loan 42's documents, newest first. */
    public static final String LOAN_42_DOCUMENT_ACCESS_LOG = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {"id": 9, "documentType": "PAYSLIP", "version": 2, "action": "VIEW", "performedBy": "cmanager", "performedAt": "2026-09-30T11:31:07+02:00"},
                {"id": 8, "documentType": "PAYSLIP", "version": 2, "action": "UPLOAD", "performedBy": "tmoyo", "performedAt": "2026-09-30T09:58:20+02:00"},
                {"id": 7, "documentType": "PAYSLIP", "version": 1, "action": "VIEW", "performedBy": "cmanager", "performedAt": "2026-09-30T09:04:51+02:00"},
                {"id": 6, "documentType": "NATIONAL_ID", "version": 1, "action": "VIEW", "performedBy": "cmanager", "performedAt": "2026-09-30T09:03:12+02:00"},
                {"id": 5, "documentType": "WITNESS_SIGNATURE", "version": 1, "action": "UPLOAD", "performedBy": "tmoyo", "performedAt": "2026-09-29T10:15:30+02:00"},
                {"id": 4, "documentType": "SIGNATURE", "version": 1, "action": "UPLOAD", "performedBy": "tmoyo", "performedAt": "2026-09-29T10:15:30+02:00"},
                {"id": 3, "documentType": "NATIONAL_ID", "version": 1, "action": "UPLOAD", "performedBy": "tmoyo", "performedAt": "2026-09-29T10:15:30+02:00"},
                {"id": 2, "documentType": "PAYSLIP", "version": 1, "action": "UPLOAD", "performedBy": "tmoyo", "performedAt": "2026-09-29T10:15:30+02:00"}
              ]
            }""";

    /**
     * An application refused for two unreadable documents (FR-SSB-005), reported together: each with the field
     * it came in, a reason a client can branch on, and a message for the applicant.
     */
    public static final String DOCUMENTS_REFUSED = """
            {
              "code": "INVALID_DOCUMENT",
              "message": "The payslip photo is too blurred to read. Please hold the camera steady and retake it in good light. The signature is blank. Please sign again.",
              "data": {
                "documents": [
                  {
                    "field": "payslipPicture",
                    "documentType": "PAYSLIP",
                    "reason": "BLURRED",
                    "message": "The payslip photo is too blurred to read. Please hold the camera steady and retake it in good light."
                  },
                  {
                    "field": "signature",
                    "documentType": "SIGNATURE",
                    "reason": "BLANK",
                    "message": "The signature is blank. Please sign again."
                  }
                ]
              }
            }""";

    // --- Application drafts (FR-SSB-002): draft 7 is started, saved a step at a time, then submitted as loan 43 ---

    /** What draft 7 was started with: the loan terms and who the applicant is. */
    public static final String DRAFT_7_START_REQUEST = """
            {
              "amount": 300.00,
              "tenor": 6,
              "ecNumber": "7654321B",
              "nationalIdNumber": "63-7654321-B-42",
              "mobileNumber": "+263772345678",
              "firstName": "Tatenda",
              "lastName": "Ncube"
            }""";

    /** Draft 7 as started: what was sent, and everything an application still needs. */
    public static final String DRAFT_7_STARTED = """
            {
              "code": "CREATED",
              "message": "Draft saved",
              "data": {
                "id": 7,
                "status": "OPEN",
                "application": {
                  "amount": 300.0,
                  "amountType": "NET_OF_FEES",
                  "ecNumber": "7654321B",
                  "firstName": "Tatenda",
                  "lastName": "Ncube",
                  "mobileNumber": "+263772345678",
                  "nationalIdNumber": "63-7654321-B-42",
                  "tenor": 6
                },
                "documents": [],
                "validationErrors": {
                  "address": "Address is required",
                  "dateOfBirth": "Date of birth is required",
                  "employmentDetail": "Employment detail is required",
                  "lineOfBusiness": "Line of business is required",
                  "loanPurpose": "Loan purpose is required",
                  "maritalStatus": "Marital status is required",
                  "nextOfKin": "Next of kin is required",
                  "placeOfBirth": "Place of birth is required"
                },
                "complete": false,
                "createdAt": "2026-09-30T09:12:04+02:00",
                "updatedAt": "2026-09-30T09:12:04+02:00"
              }
            }""";

    /** The next step of draft 7's form: only what it adds, with the payslip (base64, shortened here). */
    public static final String DRAFT_7_SAVE_REQUEST = """
            {
              "dateOfBirth": "1990-06-18",
              "maritalStatus": "SINGLE",
              "placeOfBirth": "Bulawayo",
              "address": {
                "street": "7 Jason Moyo St",
                "city": "Bulawayo"
              },
              "employmentDetail": {
                "employerName": "Government of Zimbabwe",
                "ministry": "Ministry of Health and Child Care",
                "station": "Mpilo Central Hospital",
                "contractType": "PERMANENT",
                "employeeNumber": "7654321B",
                "grossSalary": 780.00,
                "netSalary": 560.00,
                "employmentStartDate": "2015-02-02"
              },
              "payslipPicture": "JVBERi0xLjcKJcfsj6IKNSAwIG9iago8PC9MZW5ndGggNiAwIFI..."
            }""";

    /** Draft 7 after that step: the payslip saved, and four fields still to go. */
    public static final String DRAFT_7_SAVED = """
            {
              "code": "OK",
              "message": "Draft saved",
              "data": {
                "id": 7,
                "status": "OPEN",
                "application": {
                  "address": {
                    "city": "Bulawayo",
                    "street": "7 Jason Moyo St"
                  },
                  "amount": 300.0,
                  "amountType": "NET_OF_FEES",
                  "dateOfBirth": "1990-06-18",
                  "ecNumber": "7654321B",
                  "employmentDetail": {
                    "contractType": "PERMANENT",
                    "employeeNumber": "7654321B",
                    "employerName": "Government of Zimbabwe",
                    "employmentStartDate": "2015-02-02",
                    "grossSalary": 780.0,
                    "ministry": "Ministry of Health and Child Care",
                    "netSalary": 560.0,
                    "station": "Mpilo Central Hospital"
                  },
                  "firstName": "Tatenda",
                  "lastName": "Ncube",
                  "maritalStatus": "SINGLE",
                  "mobileNumber": "+263772345678",
                  "nationalIdNumber": "63-7654321-B-42",
                  "placeOfBirth": "Bulawayo",
                  "tenor": 6
                },
                "documents": [
                  {
                    "documentType": "PAYSLIP",
                    "contentType": "application/pdf",
                    "sizeBytes": 184233,
                    "sha256": "082705beaace48813c9d2e0d2a027952420df47faad71c246020f1231b2f970c",
                    "uploadedAt": "2026-09-30T09:20:41+02:00"
                  }
                ],
                "validationErrors": {
                  "employmentDetail.grade": "Grade or notch is required",
                  "lineOfBusiness": "Line of business is required",
                  "loanPurpose": "Loan purpose is required",
                  "nextOfKin": "Next of kin is required"
                },
                "complete": false,
                "createdAt": "2026-09-30T09:12:04+02:00",
                "updatedAt": "2026-09-30T09:20:41+02:00"
              }
            }""";

    /** The caller's open drafts: draft 7 at that point. */
    public static final String DRAFT_PAGE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": 7,
                    "firstName": "Tatenda",
                    "lastName": "Ncube",
                    "ecNumber": "7654321B",
                    "mobileNumber": "+263772345678",
                    "amount": 300.0,
                    "tenor": 6,
                    "validationErrorCount": 4,
                    "documents": ["PAYSLIP"],
                    "createdAt": "2026-09-30T09:12:04+02:00",
                    "updatedAt": "2026-09-30T09:20:41+02:00"
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    /** The payslip saved with draft 7, with its content (base64, shortened here). */
    public static final String DRAFT_7_PAYSLIP = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "documentType": "PAYSLIP",
                "contentType": "application/pdf",
                "sizeBytes": 184233,
                "sha256": "082705beaace48813c9d2e0d2a027952420df47faad71c246020f1231b2f970c",
                "uploadedAt": "2026-09-30T09:20:41+02:00",
                "content": "JVBERi0xLjcKJcfsj6IKNSAwIG9iago8PC9MZW5ndGggNiAwIFI..."
              }
            }""";

    /** Draft 7 submitted, once complete: loan 43, whose reference is the applicant's application reference. */
    public static final String DRAFT_7_SUBMISSION = """
            {
              "code": "CREATED",
              "message": "Loan sent for approval",
              "data": {
                "id": 43,
                "reference": "000000043",
                "ssbApprovalStatus": "NEW"
              }
            }""";

    /** Draft 7 after submission: a record of the loan it became. */
    public static final String DRAFT_7_SUBMITTED = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "id": 7,
                "status": "SUBMITTED",
                "loanId": 43,
                "loanReference": "000000043",
                "createdAt": "2026-09-30T09:12:04+02:00",
                "updatedAt": "2026-09-30T09:31:15+02:00",
                "submittedAt": "2026-09-30T09:31:15+02:00"
              }
            }""";

    // --- Signed instruments (FR-SSB-013): draft 7 is signed as loan 43 against loan agreement v3 and deduction authority v2 ---

    /** An application naming a channel that is not registered (FR-SSB-017): refused, nothing created. */
    public static final String UNKNOWN_CHANNEL = """
            {
              "code": "INVALID_REQUEST",
              "message": "No channel is registered under that channelId"
            }""";

    /** An application missing what signing needs (FR-SSB-013): every gap at once. */
    public static final String APPLICATION_NOT_SIGNED = """
            {
              "code": "VALIDATION_ERROR",
              "message": "The application is not complete",
              "data": {
                "X-Device-Id": "The signing device is required to sign the loan agreement and SSB deduction authority: send it in the X-Device-Id header",
                "deductionAuthorityVersion": "The applicant must accept the SSB deduction authority: version 2 is in force",
                "loanAgreementVersion": "The applicant must accept the loan agreement: version 3 is in force",
                "signature": "The applicant's signature is required to sign the loan agreement and SSB deduction authority"
              }
            }""";

    /** The applicant accepted wording that has since been replaced. */
    public static final String INSTRUMENT_CHANGED = """
            {
              "code": "CONFLICT",
              "message": "The loan agreement has changed since the applicant accepted version 2: version 3 is in force. Show them version 3 and ask them to accept it."
            }""";

    /** The wording in force: loan agreement version 3 and deduction authority version 2. */
    public static final String INSTRUMENT_TEMPLATES_IN_FORCE = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "instrumentType": "LOAN_AGREEMENT",
                  "version": 3,
                  "title": "SSB Loan Agreement",
                  "body": "SSB LOAN AGREEMENT\\n\\nMade on {{signedDate}} between InnBucks and {{applicantName}} (EC number {{ecNumber}}, national ID {{nationalIdNumber}}), of {{ministry}}, {{station}}.\\n\\n1. InnBucks lends the Borrower USD {{principal}}. USD {{amount}} is paid to InnBucks wallet {{walletNumber}} after an admin fee of USD {{adminFee}}.\\n2. Interest is {{interestRate}}% a month: USD {{interestAmount}} over the loan.\\n3. The Borrower repays USD {{monthlyInstalment}} a month for {{tenor}} months by deduction from salary.",
                  "publishedBy": "admin",
                  "publishedAt": "2026-09-28T14:05:12+02:00"
                },
                {
                  "instrumentType": "SSB_DEDUCTION_AUTHORITY",
                  "version": 2,
                  "title": "SSB Deduction Authority",
                  "body": "SSB DEDUCTION AUTHORITY\\n\\nI, {{applicantName}}, EC number {{ecNumber}}, employee number {{employeeNumber}}, of {{ministry}}, authorise the Salary Service Bureau to deduct USD {{monthlyDeduction}} from my salary every month for {{tenor}} months and pay it to InnBucks.\\n\\nSigned on {{signedDate}}.",
                  "publishedBy": "admin",
                  "publishedAt": "2026-09-28T14:06:30+02:00"
                }
              ]
            }""";

    /** Loan agreement version 3, as published. */
    public static final String INSTRUMENT_TEMPLATE_PUBLISHED = """
            {
              "code": "CREATED",
              "message": "LOAN_AGREEMENT version 3 published and in force",
              "data": {
                "instrumentType": "LOAN_AGREEMENT",
                "version": 3,
                "title": "SSB Loan Agreement",
                "body": "SSB LOAN AGREEMENT\\n\\nMade on {{signedDate}} between InnBucks and {{applicantName}} (EC number {{ecNumber}}, national ID {{nationalIdNumber}}), of {{ministry}}, {{station}}.\\n\\n1. InnBucks lends the Borrower USD {{principal}}. USD {{amount}} is paid to InnBucks wallet {{walletNumber}} after an admin fee of USD {{adminFee}}.\\n2. Interest is {{interestRate}}% a month: USD {{interestAmount}} over the loan.\\n3. The Borrower repays USD {{monthlyInstalment}} a month for {{tenor}} months by deduction from salary.",
                "publishedBy": "admin",
                "publishedAt": "2026-09-28T14:05:12+02:00"
              }
            }""";

    /** The wording of loan agreement version 3, as sent to publish it. */
    public static final String INSTRUMENT_TEMPLATE_PUBLISH_REQUEST = """
            {
              "instrumentType": "LOAN_AGREEMENT",
              "title": "SSB Loan Agreement",
              "body": "SSB LOAN AGREEMENT\\n\\nMade on {{signedDate}} between InnBucks and {{applicantName}} (EC number {{ecNumber}}, national ID {{nationalIdNumber}}), of {{ministry}}, {{station}}.\\n\\n1. InnBucks lends the Borrower USD {{principal}}. USD {{amount}} is paid to InnBucks wallet {{walletNumber}} after an admin fee of USD {{adminFee}}.\\n2. Interest is {{interestRate}}% a month: USD {{interestAmount}} over the loan.\\n3. The Borrower repays USD {{monthlyInstalment}} a month for {{tenor}} months by deduction from salary."
            }""";

    /** Every placeholder instrument wording may use. */
    public static final String INSTRUMENT_PLACEHOLDERS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "applicantName": "First and last name",
                "firstName": "First name",
                "lastName": "Last name",
                "ecNumber": "EC number",
                "nationalIdNumber": "National ID number",
                "mobileNumber": "Mobile number",
                "walletNumber": "InnBucks wallet the loan is paid into",
                "employerName": "Employer",
                "ministry": "Ministry or department",
                "station": "Station",
                "employeeNumber": "Employee number",
                "amount": "Amount paid to the applicant",
                "principal": "Amount borrowed",
                "adminFee": "Admin fee",
                "adminFeeRate": "Admin fee rate, percent",
                "interestRate": "Monthly interest rate, percent",
                "interestAmount": "Interest over the loan",
                "tenor": "Tenor, months",
                "monthlyInstalment": "Monthly instalment",
                "monthlyDeduction": "Monthly amount SSB is instructed to deduct",
                "signedDate": "Date signed, yyyy-MM-dd"
              }
            }""";

    /** Every version of the loan agreement, in force first. */
    public static final String INSTRUMENT_TEMPLATE_VERSIONS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "instrumentType": "LOAN_AGREEMENT",
                  "version": 3,
                  "title": "SSB Loan Agreement",
                  "body": "SSB LOAN AGREEMENT\\n\\nMade on {{signedDate}} between InnBucks and {{applicantName}} (EC number {{ecNumber}}, national ID {{nationalIdNumber}}), of {{ministry}}, {{station}}.\\n\\n1. InnBucks lends the Borrower USD {{principal}}. USD {{amount}} is paid to InnBucks wallet {{walletNumber}} after an admin fee of USD {{adminFee}}.\\n2. Interest is {{interestRate}}% a month: USD {{interestAmount}} over the loan.\\n3. The Borrower repays USD {{monthlyInstalment}} a month for {{tenor}} months by deduction from salary.",
                  "publishedBy": "admin",
                  "publishedAt": "2026-09-28T14:05:12+02:00"
                },
                {
                  "instrumentType": "LOAN_AGREEMENT",
                  "version": 2,
                  "title": "SSB Loan Agreement",
                  "body": "SSB LOAN AGREEMENT\\n\\nMade between InnBucks and {{applicantName}} (EC number {{ecNumber}}).\\n\\n1. InnBucks lends the Borrower USD {{principal}}.\\n2. The Borrower repays USD {{monthlyInstalment}} a month for {{tenor}} months.",
                  "publishedBy": "admin",
                  "publishedAt": "2026-09-14T09:40:03+02:00"
                },
                {
                  "instrumentType": "LOAN_AGREEMENT",
                  "version": 1,
                  "title": "SSB Loan Agreement",
                  "body": "SSB LOAN AGREEMENT\\n\\nBetween InnBucks and {{applicantName}}: USD {{principal}} over {{tenor}} months.",
                  "publishedBy": "admin",
                  "publishedAt": "2026-09-01T11:02:47+02:00"
                }
              ]
            }""";

    /** Loan agreement version 2, no longer in force. */
    public static final String INSTRUMENT_TEMPLATE_VERSION = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "instrumentType": "LOAN_AGREEMENT",
                "version": 2,
                "title": "SSB Loan Agreement",
                "body": "SSB LOAN AGREEMENT\\n\\nMade between InnBucks and {{applicantName}} (EC number {{ecNumber}}).\\n\\n1. InnBucks lends the Borrower USD {{principal}}.\\n2. The Borrower repays USD {{monthlyInstalment}} a month for {{tenor}} months.",
                "publishedBy": "admin",
                "publishedAt": "2026-09-14T09:40:03+02:00"
              }
            }""";

    /** Both instruments filled with draft 7's terms, as Tatenda Ncube will sign them. */
    public static final String INSTRUMENT_PREVIEW = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "instrumentType": "LOAN_AGREEMENT",
                  "version": 3,
                  "title": "SSB Loan Agreement",
                  "content": "SSB LOAN AGREEMENT\\n\\nMade on 2026-09-30 between InnBucks and Tatenda Ncube (EC number 7654321B, national ID 637654321B42), of Ministry of Health and Child Care, Mpilo Central Hospital.\\n\\n1. InnBucks lends the Borrower USD 319.15. USD 300.00 is paid to InnBucks wallet 263772345678 after an admin fee of USD 19.15.\\n2. Interest is 7% a month: USD 82.59 over the loan.\\n3. The Borrower repays USD 66.96 a month for 6 months by deduction from salary.",
                  "contentSha256": "fbf1705d85aeb6a08e44a6b3f6788f2990bc34b8509a0ce8ce86cca2e7097968"
                },
                {
                  "instrumentType": "SSB_DEDUCTION_AUTHORITY",
                  "version": 2,
                  "title": "SSB Deduction Authority",
                  "content": "SSB DEDUCTION AUTHORITY\\n\\nI, Tatenda Ncube, EC number 7654321B, employee number 7654321B, of Ministry of Health and Child Care, authorise the Salary Service Bureau to deduct USD 69.03 from my salary every month for 6 months and pay it to InnBucks.\\n\\nSigned on 2026-09-30.",
                  "contentSha256": "47e7df8ccbc8a7aa3f92577fcdc6adfca9037747e82234d67fa8300f19e43eef"
                }
              ]
            }""";

    /** Loan 43's instruments as signed when draft 7 was submitted, with the evidence of the signing. */
    public static final String LOAN_43_SIGNED_INSTRUMENTS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "instrumentType": "LOAN_AGREEMENT",
                  "templateVersion": 3,
                  "title": "SSB Loan Agreement",
                  "content": "SSB LOAN AGREEMENT\\n\\nMade on 2026-09-30 between InnBucks and Tatenda Ncube (EC number 7654321B, national ID 637654321B42), of Ministry of Health and Child Care, Mpilo Central Hospital.\\n\\n1. InnBucks lends the Borrower USD 319.15. USD 300.00 is paid to InnBucks wallet 263772345678 after an admin fee of USD 19.15.\\n2. Interest is 7% a month: USD 82.59 over the loan.\\n3. The Borrower repays USD 66.96 a month for 6 months by deduction from salary.",
                  "contentSha256": "fbf1705d85aeb6a08e44a6b3f6788f2990bc34b8509a0ce8ce86cca2e7097968",
                  "signatureSha256": "9d41a3c7e08f5b26d1c4e87a30f9b52c6e1d8a74b3f20c95e6a1d7b48c3f0e25",
                  "signedBy": "tmoyo",
                  "signedAt": "2026-09-30T09:31:15+02:00",
                  "deviceId": "a3f1c2e4-7b9d-4e21-9c55-1f0e8d6b2a77",
                  "ipAddress": "10.0.12.34",
                  "forwardedFor": "41.190.33.7",
                  "userAgent": "InnBucksPortal/2.4 (Chrome 128)",
                  "authenticationMethod": "pwd",
                  "signerAuthentication": "IN_PERSON_ID_CHECK",
                  "evidenceSha256": "afea90189a0286464dd951e741c58d1cdeec25da868107dce000e043aef4680b",
                  "intact": true
                },
                {
                  "instrumentType": "SSB_DEDUCTION_AUTHORITY",
                  "templateVersion": 2,
                  "title": "SSB Deduction Authority",
                  "content": "SSB DEDUCTION AUTHORITY\\n\\nI, Tatenda Ncube, EC number 7654321B, employee number 7654321B, of Ministry of Health and Child Care, authorise the Salary Service Bureau to deduct USD 69.03 from my salary every month for 6 months and pay it to InnBucks.\\n\\nSigned on 2026-09-30.",
                  "contentSha256": "47e7df8ccbc8a7aa3f92577fcdc6adfca9037747e82234d67fa8300f19e43eef",
                  "signatureSha256": "9d41a3c7e08f5b26d1c4e87a30f9b52c6e1d8a74b3f20c95e6a1d7b48c3f0e25",
                  "signedBy": "tmoyo",
                  "signedAt": "2026-09-30T09:31:15+02:00",
                  "deviceId": "a3f1c2e4-7b9d-4e21-9c55-1f0e8d6b2a77",
                  "ipAddress": "10.0.12.34",
                  "forwardedFor": "41.190.33.7",
                  "userAgent": "InnBucksPortal/2.4 (Chrome 128)",
                  "authenticationMethod": "pwd",
                  "signerAuthentication": "IN_PERSON_ID_CHECK",
                  "evidenceSha256": "b592bac1a1e675b664c41e26cc9b74f79359d09e919a089ca07638b9c4b4c901",
                  "intact": true
                }
              ]
            }""";

    // --- Notifications (FR-SSB-016): what loan 43's applicant was told on the day draft 7 was submitted ---

    /**
     * What loan 43's applicant was told, oldest first: received when draft 7 was submitted, sent to SSB by the
     * next lodgement run, and SSB's confirmation, which the gateway refused and is kept as not sent.
     */
    public static final String LOAN_43_NOTIFICATIONS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "notice": "RECEIVED",
                  "stage": "RECEIVED",
                  "channel": "SMS",
                  "recipient": "263772345678",
                  "message": "Your loan application with ref # 000000043 has been received. We will send it to SSB for your salary deduction and let you know the outcome. Thank you for choosing Innbucks.",
                  "gatewayReference": "LOANS-SMS-5d0c2f7e-8a41-4c6b-9f13-2e7a6b1c9d40",
                  "sent": true,
                  "attemptedAt": "2026-09-30T09:31:16+02:00"
                },
                {
                  "notice": "SENT_TO_SSB",
                  "stage": "WITH_SSB",
                  "channel": "SMS",
                  "recipient": "263772345678",
                  "message": "Your loan application with ref # 000000043 has been sent to SSB to confirm your salary deduction. You will be notified of the outcome shortly.",
                  "gatewayReference": "LOANS-SMS-a2b7e914-3c05-4f8d-b6e2-71d9c4a05f18",
                  "sent": true,
                  "attemptedAt": "2026-09-30T10:00:04+02:00"
                },
                {
                  "notice": "SSB_CONFIRMED",
                  "stage": "WITH_CREDIT",
                  "channel": "SMS",
                  "recipient": "263772345678",
                  "message": "SSB has confirmed the salary deduction for your loan application with ref # 000000043. It is now being assessed and you will be notified of the outcome.",
                  "gatewayReference": "LOANS-SMS-e61f3a08-94d2-47c1-8b5a-0c3d9e2f7a64",
                  "sent": false,
                  "failureReason": "InnBucks gateway rejected SMS: HTTP 503",
                  "attemptedAt": "2026-09-30T14:00:09+02:00"
                }
              ]
            }""";

    // --- Employment events (FR-SSB-024): loan 42's borrower is suspended, loan 43's resigns ---

    /** Recording loan 42's borrower's suspension. */
    public static final String EMPLOYMENT_EVENT_5_REQUEST = """
            {
              "ecNumber": "1234567A",
              "eventType": "SUSPENSION",
              "effectiveDate": "2026-10-01",
              "endDate": "2026-12-31",
              "note": "Suspension letter from the Public Service Commission, ref PSC/2026/0912"
            }""";

    /** What loan 42 is shown as in the event, the queue and the loan's own list: held, waiting for an officer. */
    private static final String LOAN_42_HELD = """
                {
                  "id": 11,
                  "eventId": 5,
                  "eventType": "SUSPENSION",
                  "effectiveDate": "2026-10-01",
                  "loanId": 42,
                  "loanReference": "000000042",
                  "applicantName": "Rudo Chikwanha",
                  "stage": "APPROVED",
                  "action": "HOLD",
                  "status": "OPEN",
                  "createdAt": "2026-10-02T09:14:05+02:00"
                }""";

    private static final String EMPLOYMENT_EVENT_5 = """
            {
                "id": 5,
                "ecNumber": "1234567A",
                "eventType": "SUSPENSION",
                "effectiveDate": "2026-10-01",
                "endDate": "2026-12-31",
                "note": "Suspension letter from the Public Service Commission, ref PSC/2026/0912",
                "recordedBy": "cmanager",
                "recordedAt": "2026-10-02T09:14:05+02:00",
                "loans": [
            """ + LOAN_42_HELD + """

                ]
              }""";

    /** The suspension recorded: loan 42, approved but not yet booked, is held under the default treatment. */
    public static final String EMPLOYMENT_EVENT_5_RECORDED = """
            {
              "code": "CREATED",
              "message": "Employment event recorded",
              "data": """ + EMPLOYMENT_EVENT_5 + """

            }""";

    /** Loan 43's borrower resigns: the application, just received, is declined under the default treatment. */
    public static final String EMPLOYMENT_EVENT_6_RECORDED = """
            {
              "code": "CREATED",
              "message": "Employment event recorded",
              "data": {
                "id": 6,
                "ecNumber": "7654321B",
                "eventType": "RESIGNATION",
                "effectiveDate": "2026-10-15",
                "recordedBy": "cmanager",
                "recordedAt": "2026-10-02T09:20:41+02:00",
                "loans": [
                  {
                    "id": 12,
                    "eventId": 6,
                    "eventType": "RESIGNATION",
                    "effectiveDate": "2026-10-15",
                    "loanId": 43,
                    "loanReference": "000000043",
                    "applicantName": "Tatenda Ncube",
                    "stage": "DECLINED",
                    "action": "DECLINE",
                    "status": "CLOSED",
                    "outcome": "DECLINED",
                    "comment": "Declined on the resignation effective 2026-10-15 (employment event 6)",
                    "resolvedBy": "cmanager",
                    "resolvedAt": "2026-10-02T09:20:41+02:00",
                    "createdAt": "2026-10-02T09:20:41+02:00"
                  }
                ]
              }
            }""";

    public static final String EMPLOYMENT_EVENT_5_FOUND = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + EMPLOYMENT_EVENT_5 + """

            }""";

    public static final String EMPLOYMENT_EVENT_PAGE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
            """ + EMPLOYMENT_EVENT_5 + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    /** The officers' queue: loan 42 held since the suspension. */
    public static final String LOAN_EMPLOYMENT_EVENT_QUEUE = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
            """ + LOAN_42_HELD + """

              ]
            }""";

    public static final String LOAN_EMPLOYMENT_EVENT_11_RESOLUTION = """
            {
              "outcome": "RELEASED",
              "comment": "Suspension lifted on appeal; salary restored from October"
            }""";

    /** Loan 42's hold released: it goes on to be booked and paid. */
    public static final String LOAN_EMPLOYMENT_EVENT_11_RELEASED = """
            {
              "code": "OK",
              "message": "Hold released; the application carries on",
              "data": {
                "id": 11,
                "eventId": 5,
                "eventType": "SUSPENSION",
                "effectiveDate": "2026-10-01",
                "loanId": 42,
                "loanReference": "000000042",
                "applicantName": "Rudo Chikwanha",
                "stage": "APPROVED",
                "action": "HOLD",
                "status": "CLOSED",
                "outcome": "RELEASED",
                "comment": "Suspension lifted on appeal; salary restored from October",
                "resolvedBy": "cmanager",
                "resolvedAt": "2026-10-03T11:02:17+02:00",
                "createdAt": "2026-10-02T09:14:05+02:00"
              }
            }""";

    /** Loan 42's employment events, while it is held. */
    public static final String LOAN_42_EMPLOYMENT_EVENTS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
            """ + LOAN_42_HELD + """

              ]
            }""";

    private static final String TREATMENT_ROWS = """
                {"eventType": "TRANSFER", "applicationTreatment": "CONTINUE", "loanTreatment": "NONE", "notifyOnDecline": true, "updatedBy": "system", "updatedAt": "2026-09-30T12:00:00+02:00"},
                {"eventType": "SECONDMENT", "applicationTreatment": "HOLD", "loanTreatment": "REVIEW", "notifyOnDecline": true, "updatedBy": "system", "updatedAt": "2026-09-30T12:00:00+02:00"},
                {"eventType": "PROMOTION", "applicationTreatment": "CONTINUE", "loanTreatment": "NONE", "notifyOnDecline": true, "updatedBy": "system", "updatedAt": "2026-09-30T12:00:00+02:00"},
                {"eventType": "SUSPENSION", "applicationTreatment": "HOLD", "loanTreatment": "REVIEW", "notifyOnDecline": true, "updatedBy": "system", "updatedAt": "2026-09-30T12:00:00+02:00"},
                {"eventType": "UNPAID_LEAVE", "applicationTreatment": "HOLD", "loanTreatment": "REVIEW", "notifyOnDecline": true, "updatedBy": "system", "updatedAt": "2026-09-30T12:00:00+02:00"},
                {"eventType": "RESIGNATION", "applicationTreatment": "DECLINE", "loanTreatment": "REVIEW", "notifyOnDecline": true, "updatedBy": "system", "updatedAt": "2026-09-30T12:00:00+02:00"},
                {"eventType": "RETIREMENT", "applicationTreatment": "HOLD", "loanTreatment": "REVIEW", "notifyOnDecline": true, "updatedBy": "system", "updatedAt": "2026-09-30T12:00:00+02:00"},
                {"eventType": "DEATH_IN_SERVICE", "applicationTreatment": "DECLINE", "loanTreatment": "REVIEW", "notifyOnDecline": false, "updatedBy": "system", "updatedAt": "2026-09-30T12:00:00+02:00"}""";

    /** The treatments as seeded, in the order the event types are declared. */
    public static final String EMPLOYMENT_EVENT_TREATMENTS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
            """ + TREATMENT_ROWS + """

              ]
            }""";

    public static final String EMPLOYMENT_EVENT_TREATMENT_REQUEST = """
            {
              "applicationTreatment": "CONTINUE",
              "loanTreatment": "REVIEW",
              "notifyOnDecline": true
            }""";

    /** Secondments no longer hold applications; paid loans are still reviewed. */
    public static final String EMPLOYMENT_EVENT_TREATMENT_UPDATED = """
            {
              "code": "OK",
              "message": "Treatment updated; it applies to SECONDMENT events recorded from now on",
              "data": {
                "eventType": "SECONDMENT",
                "applicationTreatment": "CONTINUE",
                "loanTreatment": "REVIEW",
                "notifyOnDecline": true,
                "updatedBy": "admin",
                "updatedAt": "2026-10-02T08:45:10+02:00"
              }
            }""";

    // --- Credit workbench and turnaround (FR-SSB-015 / FR-PBL-026, FR-PBL-030) ---

    /**
     * Loan 42's workbench at 11:03 on the 30th: resubmitted with the August payslip at 10:03, so its second wait for a
     * decision started then. The applicant's earlier loan, repaid by June, is listed but not open.
     */
    public static final String LOAN_42_CREDIT_WORKBENCH = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "loan": {
                  "id": 42,
                  "reference": "000000042",
                  "createdAt": "2026-09-29T10:15:30+02:00",
                  "createdBy": "tmoyo",
                  "createdByName": "Tendai Moyo",
                  "merchantCode": "harare-motors",
                  "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

                  "stage": "WITH_CREDIT",
                  "creditTurnaround": {
                    "queueEnteredAt": "2026-09-30T10:03:10+02:00",
                    "dueAt": "2026-10-01T10:03:10+02:00",
                    "escalatesAt": "2026-10-02T10:03:10+02:00",
                    "waitingHours": 1.0,
                    "overdue": false,
                    "assignedTo": "cmanager"
                  },
                  "creditApprovalStatus": "PENDING",
                  "bookingStatus": "PENDING",
                  "disbursementStatus": "PENDING",
                  "documents": [
                  {
            """ + PAYSLIP_V2 + """

                  },
            """ + LOAN_42_OTHER_DOCUMENTS + """

                  ]
                },
                "affordability": {
                  "grossSalary": 850.00,
                  "netSalary": 620.00,
                  "payslipDeductions": 230.00,
                  "monthlyDeduction": 208.96,
                  "netAfterDeduction": 411.04,
                  "deductionToNetPercent": 33.7,
                  "outcome": "NOT_ASSESSED",
                  "note": "The SSB deduction cap and minimum take-home pay are not configured yet, so affordability is not passed or failed; the figures are for the officer to judge."
                },
                "exposure": {
                  "openLoans": 0,
                  "openPrincipal": 0,
                  "openMonthlyDeduction": 0,
                  "loans": [
                    {
                      "id": 12,
                      "reference": "000000012",
                      "stage": "PAID",
                      "open": false,
                      "principal": 319.15,
                      "monthlyDeduction": 125.38,
                      "tenor": 3,
                      "createdAt": "2026-03-02T09:41:07+02:00",
                      "disbursedAt": "2026-03-04T14:22:51+02:00",
                      "repaymentEndDate": "2026-06-30"
                    }
                  ]
                },
                "creditAuthority": {
                  "limitsApply": false,
                  "aboveEveryLevel": false,
                  "withinYourLimit": true
                },
                "flags": [
                  {
                    "code": "DOCUMENTS_AMENDED",
                    "detail": "PAYSLIP replaced after the application (version 2) by tmoyo"
                  }
                ],
                "employmentEvents": [],
                "decisions": [
            """ + CREDIT_DECISION_17 + """
            ,
            """ + CREDIT_DECISION_18 + """

                ]
              }
            }""";

    // --- The configurable workflow (FR-SSB-014) ---

    public static final String WORKFLOW_STAGE_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "No workflow stage BOOKING"
            }""";

    /** A decision refused because the loan's credit decision is assigned to someone else at an EXCLUSIVE stage. */
    public static final String CREDIT_DECISION_ASSIGNED_ELSEWHERE = """
            {
              "code": "CONFLICT",
              "message": "Loan 000000042's Credit decision is assigned to rnyathi; only they can act on it until it is released or reassigned"
            }""";

    public static final String WORK_ITEM_ASSIGN_FORBIDDEN = """
            {
              "code": "FORBIDDEN",
              "message": "You may take Credit decision items for yourself, but not give them to others"
            }""";

    private static final String STAGE_PAYSLIP_REVIEW = """
                {
                  "code": "PAYSLIP_REVIEW",
                  "kind": "SYSTEM",
                  "name": "Payslip review",
                  "description": "Clear or confirm an application held for a payslip finding",
                  "displayOrder": 10,
                  "active": true,
                  "assignment": "OPTIONAL",
                  "viewRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "workRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "assignRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "targetHours": 24,
                  "escalationHours": 48,
                  "escalateTo": ["SUPER_ADMIN"],
                  "notifyAssignee": true,
                  "updatedBy": "system",
                  "updatedAt": "2026-09-30T12:00:00+02:00"
                }""";

    private static final String STAGE_CREDIT_DECISION = """
                {
                  "code": "CREDIT_DECISION",
                  "kind": "SYSTEM",
                  "name": "Credit decision",
                  "description": "Approve, reject or return an application SSB has accepted",
                  "displayOrder": 20,
                  "active": true,
                  "assignment": "OPTIONAL",
                  "viewRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "workRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "assignRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "targetHours": 24,
                  "escalationHours": 48,
                  "escalateTo": ["SUPER_ADMIN"],
                  "notifyAssignee": true,
                  "updatedBy": "system",
                  "updatedAt": "2026-09-30T12:00:00+02:00"
                }""";

    private static final String STAGE_MORE_INFORMATION = """
                {
                  "code": "MORE_INFORMATION",
                  "kind": "SYSTEM",
                  "name": "More information",
                  "description": "The originator answers a return from Credit",
                  "displayOrder": 30,
                  "active": true,
                  "assignment": "NONE",
                  "viewRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "workRoles": [],
                  "assignRoles": [],
                  "targetHours": 48,
                  "escalationHours": 96,
                  "escalateTo": ["SUPER_ADMIN"],
                  "notifyAssignee": true,
                  "updatedBy": "system",
                  "updatedAt": "2026-09-30T12:00:00+02:00"
                }""";

    /**
     * A checkpoint an administrator added on the 29th: a credit manager checks an application of 1,000 or more captured
     * on the portal against the originals before its deduction is lodged.
     */
    private static final String STAGE_AGENT_APPLICATION_REVIEW = """
                {
                  "code": "AGENT_APPLICATION_REVIEW",
                  "kind": "CHECKPOINT",
                  "name": "Agent application review",
                  "description": "A credit manager checks an application captured on the portal against the originals before it is lodged",
                  "displayOrder": 15,
                  "holdPoint": "BEFORE_LODGEMENT",
                  "minimumPrincipal": 1000.00,
                  "channels": ["PORTAL"],
                  "active": true,
                  "activeSince": "2026-09-29T09:15:04+02:00",
                  "assignment": "OPTIONAL",
                  "viewRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "workRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "assignRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "targetHours": 8,
                  "escalationHours": 24,
                  "escalateTo": ["SUPER_ADMIN"],
                  "notifyAssignee": true,
                  "updatedBy": "admin",
                  "updatedAt": "2026-09-29T09:15:04+02:00"
                }""";

    /**
     * The payout authorisation (FR-SSB-018) as seeded, and switched on by an administrator on the 2nd: no loan Credit
     * approves is paid until Finance authorises its payout.
     */
    private static final String STAGE_PAYOUT_AUTHORISATION = """
                {
                  "code": "PAYOUT_AUTHORISATION",
                  "kind": "CHECKPOINT",
                  "name": "Payout authorisation",
                  "description": "Authorise the payout of a loan Credit has approved, or decline it",
                  "displayOrder": 35,
                  "holdPoint": "BEFORE_BOOKING",
                  "channels": [],
                  "active": true,
                  "activeSince": "2026-10-02T09:15:04+02:00",
                  "assignment": "OPTIONAL",
                  "viewRoles": ["SUPER_ADMIN", "CREDIT_MANAGER", "FINANCE"],
                  "workRoles": ["SUPER_ADMIN", "FINANCE"],
                  "assignRoles": ["SUPER_ADMIN", "FINANCE"],
                  "targetHours": 4,
                  "escalationHours": 8,
                  "escalateTo": ["SUPER_ADMIN", "FINANCE"],
                  "notifyAssignee": true,
                  "updatedBy": "admin",
                  "updatedAt": "2026-10-02T09:15:04+02:00"
                }""";

    private static final String STAGE_LATER = """
                {
                  "code": "EMPLOYMENT_EVENT_REVIEW",
                  "kind": "SYSTEM",
                  "name": "Employment event review",
                  "description": "Release or decline an application held for an employment event, or review a paid loan",
                  "displayOrder": 40,
                  "active": true,
                  "assignment": "OPTIONAL",
                  "viewRoles": ["SUPER_ADMIN", "CREDIT_MANAGER", "FINANCE"],
                  "workRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "assignRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                  "targetHours": 48,
                  "escalationHours": 96,
                  "escalateTo": ["SUPER_ADMIN"],
                  "notifyAssignee": true,
                  "updatedBy": "system",
                  "updatedAt": "2026-09-30T12:00:00+02:00"
                },
                {
                  "code": "DEDUCTION_CANCELLATION",
                  "kind": "SYSTEM",
                  "name": "Deduction cancellation",
                  "description": "Cancel the SSB deduction of a loan that will not be paid, and record it",
                  "displayOrder": 50,
                  "active": true,
                  "assignment": "OPTIONAL",
                  "viewRoles": ["SUPER_ADMIN", "CREDIT_MANAGER", "FINANCE"],
                  "workRoles": ["SUPER_ADMIN", "FINANCE"],
                  "assignRoles": ["SUPER_ADMIN", "FINANCE"],
                  "targetHours": 24,
                  "escalationHours": 48,
                  "escalateTo": ["SUPER_ADMIN"],
                  "notifyAssignee": true,
                  "updatedBy": "system",
                  "updatedAt": "2026-09-30T12:00:00+02:00"
                }""";

    /**
     * The stages as seeded (who could do what before the workflow was configurable, and a service level each), with
     * the payout authorisation switched on and the checkpoint an administrator added, in display order.
     */
    public static final String WORKFLOW_STAGES = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
            """ + STAGE_PAYSLIP_REVIEW + """
            ,
            """ + STAGE_AGENT_APPLICATION_REVIEW + """
            ,
            """ + STAGE_CREDIT_DECISION + """
            ,
            """ + STAGE_MORE_INFORMATION + """
            ,
            """ + STAGE_PAYOUT_AUTHORISATION + """
            ,
            """ + STAGE_LATER + """

              ]
            }""";

    public static final String WORKFLOW_STAGE_CREDIT_DECISION = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + STAGE_CREDIT_DECISION + """

            }""";

    public static final String WORKFLOW_STAGE_REQUEST = """
            {
              "name": "Credit decision",
              "description": "Approve, reject or return an application SSB has accepted",
              "assignment": "EXCLUSIVE",
              "viewRoles": ["CREDIT_MANAGER", "FINANCE"],
              "workRoles": ["CREDIT_MANAGER"],
              "assignRoles": ["CREDIT_MANAGER"],
              "targetHours": 8,
              "escalationHours": 16,
              "escalateTo": ["SUPER_ADMIN", "CREDIT_MANAGER"],
              "notifyAssignee": true
            }""";

    /** Credit decisions tightened to a working day, made exclusive to their assignee, and visible to Finance. */
    public static final String WORKFLOW_STAGE_UPDATED = """
            {
              "code": "OK",
              "message": "Workflow stage updated; it applies to items already waiting as well",
              "data": {
                "code": "CREDIT_DECISION",
                "kind": "SYSTEM",
                "name": "Credit decision",
                "description": "Approve, reject or return an application SSB has accepted",
                "displayOrder": 20,
                "active": true,
                "assignment": "EXCLUSIVE",
                "viewRoles": ["SUPER_ADMIN", "CREDIT_MANAGER", "FINANCE"],
                "workRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                "assignRoles": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                "targetHours": 8,
                "escalationHours": 16,
                "escalateTo": ["SUPER_ADMIN", "CREDIT_MANAGER"],
                "notifyAssignee": true,
                "updatedBy": "admin",
                "updatedAt": "2026-10-02T08:50:31+02:00"
              }
            }""";

    /** Switching the payout authorisation on, leaving the rest of its seeded configuration as it was. */
    public static final String PAYOUT_AUTHORISATION_ON_REQUEST = """
            {
              "name": "Payout authorisation",
              "description": "Authorise the payout of a loan Credit has approved, or decline it",
              "assignment": "OPTIONAL",
              "viewRoles": ["CREDIT_MANAGER", "FINANCE"],
              "workRoles": ["FINANCE"],
              "assignRoles": ["FINANCE"],
              "targetHours": 4,
              "escalationHours": 8,
              "escalateTo": ["SUPER_ADMIN", "FINANCE"],
              "notifyAssignee": true,
              "active": true
            }""";

    public static final String PAYOUT_AUTHORISATION_ON = """
            {
              "code": "OK",
              "message": "Workflow stage updated; it applies to items already waiting as well",
              "data": """ + STAGE_PAYOUT_AUTHORISATION + """

            }""";

    public static final String CHECKPOINT_STAGE_REQUEST = """
            {
              "code": "AGENT_APPLICATION_REVIEW",
              "holdPoint": "BEFORE_LODGEMENT",
              "name": "Agent application review",
              "description": "A credit manager checks an application captured on the portal against the originals before it is lodged",
              "minimumPrincipal": 1000.00,
              "channels": ["PORTAL"],
              "assignment": "OPTIONAL",
              "viewRoles": ["CREDIT_MANAGER"],
              "workRoles": ["CREDIT_MANAGER"],
              "assignRoles": ["CREDIT_MANAGER"],
              "targetHours": 8,
              "escalationHours": 24,
              "escalateTo": ["SUPER_ADMIN"],
              "notifyAssignee": true
            }""";

    public static final String CHECKPOINT_STAGE_CREATED = """
            {
              "code": "CREATED",
              "message": "Checkpoint stage created; it holds loans at BEFORE_LODGEMENT from now",
              "data": """ + STAGE_AGENT_APPLICATION_REVIEW + """

            }""";

    public static final String CHECKPOINT_STAGE_EXISTS = """
            {
              "code": "CONFLICT",
              "message": "Workflow stage AGENT_APPLICATION_REVIEW already exists"
            }""";

    public static final String CHECKPOINT_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "No checkpoint stage CREDIT_DECISION"
            }""";

    public static final String CHECKPOINT_CLEAR_REQUEST = """
            {
              "outcome": "CLEARED",
              "comment": "Payout wallet confirmed with the applicant by phone"
            }""";

    public static final String CHECKPOINT_DECLINE_REQUEST = """
            {
              "outcome": "DECLINED",
              "reasonCode": "REJECT_IDENTITY",
              "comment": "The payout wallet is registered to someone other than the applicant"
            }""";

    public static final String CHECKPOINT_CLEARED = """
            {
              "code": "OK",
              "message": "Cleared; the loan carries on",
              "data": {
                "stage": "PAYOUT_AUTHORISATION",
                "stageName": "Payout authorisation",
                "holdPoint": "BEFORE_BOOKING",
                "loanId": 61,
                "reference": "000000061",
                "enteredAt": "2026-10-02T09:41:12+02:00",
                "outcome": "CLEARED",
                "comment": "Payout wallet confirmed with the applicant by phone",
                "decidedBy": "finance1",
                "decidedAt": "2026-10-02T11:02:47+02:00"
              }
            }""";

    public static final String CHECKPOINT_DECLINED = """
            {
              "code": "OK",
              "message": "The application is declined",
              "data": {
                "stage": "PAYOUT_AUTHORISATION",
                "stageName": "Payout authorisation",
                "holdPoint": "BEFORE_BOOKING",
                "loanId": 61,
                "reference": "000000061",
                "enteredAt": "2026-10-02T09:41:12+02:00",
                "outcome": "DECLINED",
                "reasonCode": "REJECT_IDENTITY",
                "comment": "The payout wallet is registered to someone other than the applicant",
                "decidedBy": "finance1",
                "decidedAt": "2026-10-02T11:02:47+02:00"
              }
            }""";

    /**
     * Loan 61's checkpoints on the morning of the 2nd: waiting for its payout to be authorised, with {@code finance1},
     * having been cleared at the agent application review before it was lodged.
     */
    public static final String LOAN_61_CHECKPOINTS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "stage": "PAYOUT_AUTHORISATION",
                  "name": "Payout authorisation",
                  "holdPoint": "BEFORE_BOOKING",
                  "status": "PENDING",
                  "enteredAt": "2026-10-02T09:41:12+02:00",
                  "assignedTo": "finance1"
                },
                {
                  "stage": "AGENT_APPLICATION_REVIEW",
                  "name": "Agent application review",
                  "holdPoint": "BEFORE_LODGEMENT",
                  "status": "CLEARED",
                  "enteredAt": "2026-09-29T14:20:05+02:00",
                  "comment": "Identity and payslip checked against the originals",
                  "decidedBy": "cmanager",
                  "decidedAt": "2026-09-29T15:02:31+02:00"
                }
              ]
            }""";

    /**
     * The queues as {@code cmanager} sees them at 11:03 on the 30th, before loan 42 is approved. The payout
     * authorisation is not switched on until the 2nd, and a stage that is off has no queue.
     */
    public static final String WORK_QUEUES = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                { "stage": "PAYSLIP_REVIEW", "name": "Payslip review", "assignment": "OPTIONAL", "targetHours": 24, "escalationHours": 48, "waiting": 1, "overdue": 0, "escalated": 0, "unassigned": 1, "assignedToMe": 0 },
                { "stage": "AGENT_APPLICATION_REVIEW", "name": "Agent application review", "assignment": "OPTIONAL", "targetHours": 8, "escalationHours": 24, "waiting": 0, "overdue": 0, "escalated": 0, "unassigned": 0, "assignedToMe": 0 },
                { "stage": "CREDIT_DECISION", "name": "Credit decision", "assignment": "OPTIONAL", "targetHours": 24, "escalationHours": 48, "waiting": 2, "overdue": 1, "escalated": 1, "unassigned": 1, "assignedToMe": 1 },
                { "stage": "MORE_INFORMATION", "name": "More information", "assignment": "NONE", "targetHours": 48, "escalationHours": 96, "waiting": 0, "overdue": 0, "escalated": 0 },
                { "stage": "EMPLOYMENT_EVENT_REVIEW", "name": "Employment event review", "assignment": "OPTIONAL", "targetHours": 48, "escalationHours": 96, "waiting": 0, "overdue": 0, "escalated": 0, "unassigned": 0, "assignedToMe": 0 },
                { "stage": "DEDUCTION_CANCELLATION", "name": "Deduction cancellation", "assignment": "OPTIONAL", "targetHours": 24, "escalationHours": 48, "waiting": 1, "overdue": 0, "escalated": 0, "unassigned": 1, "assignedToMe": 0 }
              ]
            }""";

    /** Loan 42 in the credit queue after its resubmission, reassigned to cmanager at 10:31. */
    private static final String WORK_ITEM_42 = """
                {
                  "stage": "CREDIT_DECISION",
                  "loanId": 42,
                  "reference": "000000042",
                  "applicantName": "Rudo Chikwanha",
                  "principal": 531.91,
                  "originator": "tmoyo",
                  "originatorName": "Tendai Moyo",
                  "enteredAt": "2026-09-30T10:03:10+02:00",
                  "dueAt": "2026-10-01T10:03:10+02:00",
                  "escalatesAt": "2026-10-02T10:03:10+02:00",
                  "waitingHours": 1.0,
                  "overdue": false,
                  "assignedTo": "cmanager",
                  "assignedToName": "Chipo Manyika",
                  "assignedAt": "2026-09-30T10:31:18+02:00"
                }""";

    /** The credit queue at 11:03 on the 30th: loan 44, from the SuperApp, overdue and escalated with nobody on it. */
    public static final String WORK_QUEUE_CREDIT_DECISION = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "stage": "CREDIT_DECISION",
                  "loanId": 44,
                  "reference": "000000044",
                  "applicantName": "Tafadzwa Ncube",
                  "principal": 319.15,
                  "channelId": "superapp",
                  "channelName": "InnBucks SuperApp",
                  "originator": "superapp",
                  "enteredAt": "2026-09-28T09:10:00+02:00",
                  "dueAt": "2026-09-29T09:10:00+02:00",
                  "escalatesAt": "2026-09-30T09:10:00+02:00",
                  "waitingHours": 49.9,
                  "overdue": true,
                  "escalatedAt": "2026-09-30T09:15:00+02:00"
                },
            """ + WORK_ITEM_42 + """

              ]
            }""";

    /** What cmanager has, across every stage they see. */
    public static final String MY_WORK_ITEMS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
            """ + WORK_ITEM_42 + """

              ]
            }""";

    public static final String WORK_ITEM_ASSIGN_REQUEST = """
            {
              "assignee": "cmanager"
            }""";

    /** admin gives loan 42 to cmanager while rnyathi is on leave. */
    public static final String WORK_ITEM_ASSIGNED = """
            {
              "code": "OK",
              "message": "Assigned to cmanager",
              "data": """ + WORK_ITEM_42 + """

            }""";

    /** cmanager hands loan 42 back to the queue. */
    public static final String WORK_ITEM_RELEASED = """
            {
              "code": "OK",
              "message": "Released",
              "data": {
                "stage": "CREDIT_DECISION",
                "loanId": 42,
                "reference": "000000042",
                "applicantName": "Rudo Chikwanha",
                "principal": 531.91,
                "originator": "tmoyo",
                "originatorName": "Tendai Moyo",
                "enteredAt": "2026-09-30T10:03:10+02:00",
                "dueAt": "2026-10-01T10:03:10+02:00",
                "escalatesAt": "2026-10-02T10:03:10+02:00",
                "waitingHours": 1.0,
                "overdue": false
              }
            }""";

    /**
     * Loan 42's work items: cmanager took its first wait, which ended in the return; its second wait was given to
     * rnyathi, then reassigned to cmanager, who approved it.
     */
    public static final String LOAN_42_WORK_HISTORY = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                { "id": 1, "stage": "CREDIT_DECISION", "loanId": 42, "enteredAt": "2026-09-30T08:05:12+02:00", "action": "ASSIGNED", "toUser": "cmanager", "performedBy": "cmanager", "performedAt": "2026-09-30T08:47:03+02:00" },
                { "id": 2, "stage": "CREDIT_DECISION", "loanId": 42, "enteredAt": "2026-09-30T10:03:10+02:00", "action": "ASSIGNED", "toUser": "rnyathi", "performedBy": "admin", "performedAt": "2026-09-30T10:05:40+02:00" },
                { "id": 3, "stage": "CREDIT_DECISION", "loanId": 42, "enteredAt": "2026-09-30T10:03:10+02:00", "action": "REASSIGNED", "fromUser": "rnyathi", "toUser": "cmanager", "performedBy": "admin", "performedAt": "2026-09-30T10:31:18+02:00" }
              ]
            }""";

    /**
     * The pipeline for September. The credit decision figures are the credit turnaround report's; the other stages
     * are shown as they stood.
     */
    public static final String WORKFLOW_PIPELINE_REPORT = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "fromDate": "2026-09-01",
                "toDate": "2026-09-30",
                "stages": [
                  {
                    "stage": "PAYSLIP_REVIEW", "name": "Payslip review", "targetHours": 24, "escalationHours": 48,
                    "waiting": 1, "overdue": 0, "escalated": 0, "unassigned": 1,
                    "completed": 2, "withinTarget": 2, "adherencePercent": 100.0, "averageHours": 3.5, "medianHours": 3.5, "longestHours": 5.1, "unmeasured": 0,
                    "byChannel": [
                      { "key": "PORTAL", "name": "Portal", "waiting": 1, "overdue": 0, "completed": 2, "withinTarget": 2, "adherencePercent": 100.0, "averageHours": 3.5 }
                    ],
                    "byOriginator": [
                      { "key": "tmoyo", "name": "Tendai Moyo", "waiting": 1, "overdue": 0, "completed": 2, "withinTarget": 2, "adherencePercent": 100.0, "averageHours": 3.5 }
                    ]
                  },
                  {
                    "stage": "CREDIT_DECISION", "name": "Credit decision", "targetHours": 24, "escalationHours": 48,
                    "waiting": 3, "overdue": 1, "escalated": 0, "unassigned": 3,
                    "completed": 14, "withinTarget": 12, "adherencePercent": 85.7, "averageHours": 13.4, "medianHours": 6.2, "longestHours": 52.3, "unmeasured": 0,
                    "byChannel": [
                      { "key": "PORTAL", "name": "Portal", "waiting": 2, "overdue": 1, "completed": 11, "withinTarget": 10, "adherencePercent": 90.9, "averageHours": 10.8 },
                      { "key": "superapp", "name": "InnBucks SuperApp", "waiting": 1, "overdue": 0, "completed": 3, "withinTarget": 2, "adherencePercent": 66.7, "averageHours": 22.9 }
                    ],
                    "byOriginator": [
                      { "key": "superapp", "waiting": 1, "overdue": 0, "completed": 3, "withinTarget": 2, "adherencePercent": 66.7, "averageHours": 22.9 },
                      { "key": "tmoyo", "name": "Tendai Moyo", "waiting": 2, "overdue": 1, "completed": 11, "withinTarget": 10, "adherencePercent": 90.9, "averageHours": 10.8 }
                    ]
                  },
                  {
                    "stage": "MORE_INFORMATION", "name": "More information", "targetHours": 48, "escalationHours": 96,
                    "waiting": 0, "overdue": 0, "escalated": 0,
                    "completed": 2, "withinTarget": 2, "adherencePercent": 100.0, "averageHours": 0.9, "medianHours": 0.9, "longestHours": 0.9, "unmeasured": 0,
                    "byChannel": [
                      { "key": "PORTAL", "name": "Portal", "waiting": 0, "overdue": 0, "completed": 2, "withinTarget": 2, "adherencePercent": 100.0, "averageHours": 0.9 }
                    ],
                    "byOriginator": [
                      { "key": "tmoyo", "name": "Tendai Moyo", "waiting": 0, "overdue": 0, "completed": 2, "withinTarget": 2, "adherencePercent": 100.0, "averageHours": 0.9 }
                    ]
                  },
                  {
                    "stage": "EMPLOYMENT_EVENT_REVIEW", "name": "Employment event review", "targetHours": 48, "escalationHours": 96,
                    "waiting": 0, "overdue": 0, "escalated": 0, "unassigned": 0,
                    "completed": 0, "withinTarget": 0, "unmeasured": 0, "byChannel": [], "byOriginator": []
                  },
                  {
                    "stage": "DEDUCTION_CANCELLATION", "name": "Deduction cancellation", "targetHours": 24, "escalationHours": 48,
                    "waiting": 1, "overdue": 0, "escalated": 0, "unassigned": 1,
                    "completed": 0, "withinTarget": 0, "unmeasured": 0,
                    "byChannel": [
                      { "key": "PORTAL", "name": "Portal", "waiting": 1, "overdue": 0, "completed": 0, "withinTarget": 0 }
                    ],
                    "byOriginator": [
                      { "key": "tmoyo", "name": "Tendai Moyo", "waiting": 1, "overdue": 0, "completed": 0, "withinTarget": 0 }
                    ]
                  }
                ]
              }
            }""";

    /**
     * Credit's turnaround for September against the seeded service level: loan 42's return took 1.1 hours and its
     * approval 1.6, among fourteen decisions.
     */
    public static final String CREDIT_TURNAROUND_REPORT = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "fromDate": "2026-09-01",
                "toDate": "2026-09-30",
                "targetHours": 24,
                "escalationHours": 48,
                "decisions": 14,
                "withinTarget": 12,
                "adherencePercent": 85.7,
                "averageHours": 13.4,
                "medianHours": 6.2,
                "longestHours": 52.3,
                "unmeasured": 0,
                "byAction": [
                  { "action": "APPROVED", "decisions": 9, "withinTarget": 8, "averageHours": 11.9 },
                  { "action": "REJECTED", "decisions": 3, "withinTarget": 2, "averageHours": 22.6 },
                  { "action": "RETURNED", "decisions": 2, "withinTarget": 2, "averageHours": 6.3 }
                ],
                "awaiting": 3,
                "overdue": 1,
                "escalated": 0
              }
            }""";

    // ---- Credit approval limits and referral (FR-PBL-028) ----

    /** Credit officers approve up to 1,000; set up by admin on 3 October, with cmanager given the level. */
    private static final String AUTHORITY_CREDIT_OFFICER = """
                {
                  "code": "CREDIT_OFFICER",
                  "name": "Credit officer",
                  "maximumPrincipal": 1000.00,
                  "holders": ["cmanager"],
                  "updatedBy": "admin",
                  "updatedAt": "2026-10-03T08:29:40+02:00"
                }""";

    /** Senior credit officers: added at 2,500, raised to 3,000 once rnyathi held it. */
    private static final String AUTHORITY_SENIOR_CREDIT_OFFICER = """
                {
                  "code": "SENIOR_CREDIT_OFFICER",
                  "name": "Senior credit officer",
                  "maximumPrincipal": 3000.00,
                  "holders": ["rnyathi"],
                  "updatedBy": "admin",
                  "updatedAt": "2026-10-03T08:41:05+02:00"
                }""";

    /** The Head of Credit approves any amount. */
    private static final String AUTHORITY_HEAD_OF_CREDIT = """
                {
                  "code": "HEAD_OF_CREDIT",
                  "name": "Head of Credit",
                  "holders": ["pmutasa"],
                  "updatedBy": "admin",
                  "updatedAt": "2026-10-03T08:31:02+02:00"
                }""";

    /** The levels on 3 October, lowest limit first. */
    public static final String CREDIT_AUTHORITY_LEVELS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
            """ + AUTHORITY_CREDIT_OFFICER + """
            ,
            """ + AUTHORITY_SENIOR_CREDIT_OFFICER + """
            ,
            """ + AUTHORITY_HEAD_OF_CREDIT + """

              ]
            }""";

    public static final String CREDIT_AUTHORITY_LEVEL_REQUEST = """
            {
              "code": "SENIOR_CREDIT_OFFICER",
              "name": "Senior credit officer",
              "maximumPrincipal": 2500.00
            }""";

    public static final String CREDIT_AUTHORITY_LEVEL_CREATED = """
            {
              "code": "CREATED",
              "message": "Credit authority level created; it limits approvals by whoever is given it",
              "data": {
                "code": "SENIOR_CREDIT_OFFICER",
                "name": "Senior credit officer",
                "maximumPrincipal": 2500.00,
                "holders": [],
                "updatedBy": "admin",
                "updatedAt": "2026-10-03T08:30:25+02:00"
              }
            }""";

    public static final String CREDIT_AUTHORITY_LEVEL_UPDATE_REQUEST = """
            {
              "name": "Senior credit officer",
              "maximumPrincipal": 3000.00
            }""";

    public static final String CREDIT_AUTHORITY_LEVEL_UPDATED = """
            {
              "code": "OK",
              "message": "Credit authority level updated; it applies to the next approval",
              "data": """ + AUTHORITY_SENIOR_CREDIT_OFFICER + """

            }""";

    public static final String CREDIT_AUTHORITY_LEVEL_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "No credit authority level JUNIOR_OFFICER"
            }""";

    /** rnyathi given the senior level. */
    public static final String USER_CREDIT_AUTHORITY_REQUEST = """
            {
              "level": "SENIOR_CREDIT_OFFICER"
            }""";

    public static final String USER_CREDIT_AUTHORITY_CHANGED = """
            {
              "code": "OK",
              "message": "Credit authority level set to SENIOR_CREDIT_OFFICER",
              "data": {
                "id": 3,
                "username": "rnyathi",
                "firstName": "Rufaro",
                "lastName": "Nyathi",
                "email": "rufaro.nyathi@innbucks.co.zw",
                "mobileNumber": "263772345111",
                "temporaryPassword": false,
                "groups": ["CREDIT_MANAGER"],
                "merchantCode": "innbucks",
                "merchantName": "InnBucks MicroBank",
                "creditAuthorityLevel": "SENIOR_CREDIT_OFFICER"
              }
            }""";

    /** Loan 64, for 2,659.57, as cmanager (a credit officer, limit 1,000) referred it. */
    private static final String LOAN_64_SNAPSHOT =
            "{\"reference\":\"000000064\",\"ecNumber\":\"2345678B\",\"firstName\":\"Farai\",\"lastName\":\"Dube\","
            + "\"merchantCode\":\"harare-motors\",\"originator\":\"tmoyo\",\"ssbApprovalStatus\":\"APPROVED\",\"ssbDeductionId\":\"88377\","
            + "\"batchNumber\":\"B-20261003-1\",\"principal\":2659.57,\"disbursedAmount\":2500.00,\"tenor\":12,\"interestRate\":7.00,"
            + "\"feeRate\":6.00,\"monthlyInstallment\":256.40,\"grossedMonthlyDeduction\":264.33,"
            + "\"employment\":{\"employerName\":\"Government of Zimbabwe\",\"ministry\":\"Ministry of Health and Child Care\","
            + "\"station\":\"Parirenyatwa Group of Hospitals\",\"grade\":\"E1\",\"contractType\":\"PERMANENT\",\"dateOfEngagement\":\"2009-03-02\","
            + "\"grossSalary\":1420.00,\"netSalary\":1010.00},"
            + "\"payslipDeductions\":[{\"beneficiary\":\"ZIMRA PAYE\",\"amount\":268.00},{\"beneficiary\":\"PSMAS medical aid\",\"amount\":71.00},"
            + "{\"beneficiary\":\"APEX pension\",\"amount\":71.00}],"
            + "\"payoutType\":\"CUSTOMER_MOBILE_WALLET\",\"payoutAccount\":\"****8834\","
            + "\"documents\":{\"payslipPictureSha256\":\"5b1f0e7c2a9d4f6b8e3c1a7d9f2b4e6c8a0d3f5b7e9c1a3d5f7b9e2c4a6d8f0b\","
            + "\"nationalIdPictureSha256\":\"9e2d4b6f8a1c3e5d7b9f0a2c4e6d8b1f3a5c7e9d0b2f4a6c8e1d3b5f7a9c0e2d\","
            + "\"signatureSha256\":\"3c5e7a9b1d2f4e6c8a0b3d5f7e9a1c2b4d6f8e0a3c5b7d9f1e2a4c6b8d0f3e5a\"}}";

    public static final String CREDIT_REFERRAL_REQUEST = """
            {
              "recommendation": "APPROVED",
              "reasonCode": "APPROVE_WITHIN_POLICY",
              "comment": "Payslip and deduction capacity verified; above my limit"
            }""";

    public static final String LOAN_64_REFERRED = """
            {
              "code": "OK",
              "message": "Loan referred to SENIOR_CREDIT_OFFICER",
              "data": {
                "id": 31,
                "action": "REFERRED",
                "reasonCode": "APPROVE_WITHIN_POLICY",
                "reasonDescription": "Meets credit policy",
                "comment": "Payslip and deduction capacity verified; above my limit",
                "performedBy": "cmanager",
                "performedAt": "2026-10-03T10:12:40+02:00",
                "referredTo": "SENIOR_CREDIT_OFFICER",
                "recommendation": "APPROVED",
                "loanSnapshot": """ + LOAN_64_SNAPSHOT + """
            ,
                "snapshotSha256": "4d8c2f6a0e1b3d5c7f9a2e4b6d8f0c1a3e5b7d9f2a4c6e8b0d1f3a5c7e9b2d4f"
              }
            }""";

    public static final String APPROVAL_ABOVE_LIMIT = """
            {
              "code": "FORBIDDEN",
              "message": "Loan 000000064 is for 2659.57, above cmanager's approval limit of 1000.00 (Credit officer); refer it to Senior credit officer or above"
            }""";

    public static final String APPROVAL_WITHOUT_LEVEL = """
            {
              "code": "FORBIDDEN",
              "message": "gmoyo has no credit approval limit, so cannot approve loan 000000064; refer it to Senior credit officer or above"
            }""";

    // ---- Staff Grocery Loan grade-to-limit matrix (FR-SGL-009, FR-SGL-010) ----
    // Change 1 put grade C4 at 300.00 from 2026-10-01 (proposed by credit1, approved by credit2); change 3 put D1 at
    // 500.00 the same day; change 2, proposed on 1 October, raises C4 to 350.00 from 1 November.

    private static final String GRADE_LIMIT_C4_1 = """
                {
                  "changeId": 1,
                  "grade": "C4",
                  "scoreBand": "Band C",
                  "maximumLimit": 300.00,
                  "effectiveFrom": "2026-10-01",
                  "approvedBy": "credit2",
                  "approvedAt": "2026-09-30T10:05:11+02:00"
                }""";

    private static final String GRADE_LIMIT_D1_3 = """
                {
                  "changeId": 3,
                  "grade": "D1",
                  "scoreBand": "Band D",
                  "maximumLimit": 500.00,
                  "effectiveFrom": "2026-10-01",
                  "approvedBy": "credit2",
                  "approvedAt": "2026-09-30T10:06:40+02:00"
                }""";

    public static final String STAFF_GRADE_LIMITS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "grade": "C4",
                  "current": """ + GRADE_LIMIT_C4_1 + """
            ,
                  "scheduled": [],
                  "pendingChanges": 1
                },
                {
                  "grade": "D1",
                  "current": """ + GRADE_LIMIT_D1_3 + """
            ,
                  "scheduled": [],
                  "pendingChanges": 0
                }
              ]
            }""";

    public static final String STAFF_GRADE_LIMIT_PROPOSAL = """
            {
              "grade": "C4",
              "scoreBand": "Band C",
              "maximumLimit": 350.00,
              "effectiveFrom": "2026-11-01",
              "comment": "Annual review approved by Credit and Human Capital"
            }""";

    private static final String GRADE_LIMIT_CHANGE_2_PENDING = """
                {
                  "id": 2,
                  "grade": "C4",
                  "scoreBand": "Band C",
                  "maximumLimit": 350.00,
                  "effectiveFrom": "2026-11-01",
                  "status": "PENDING",
                  "proposedBy": "credit1",
                  "proposedAt": "2026-10-01T08:30:02+02:00",
                  "comment": "Annual review approved by Credit and Human Capital",
                  "replacing": """ + GRADE_LIMIT_C4_1 + """

                }""";

    public static final String STAFF_GRADE_LIMIT_PROPOSED = """
            {
              "code": "CREATED",
              "message": "Grade limit change proposed; it applies once someone else approves it",
              "data": """ + GRADE_LIMIT_CHANGE_2_PENDING + """

            }""";

    public static final String STAFF_GRADE_LIMIT_CHANGES = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
            """ + GRADE_LIMIT_CHANGE_2_PENDING + """
            ,
                {
                  "id": 1,
                  "grade": "C4",
                  "scoreBand": "Band C",
                  "maximumLimit": 300.00,
                  "effectiveFrom": "2026-10-01",
                  "status": "APPROVED",
                  "proposedBy": "credit1",
                  "proposedAt": "2026-09-30T09:12:40+02:00",
                  "comment": "Initial matrix approved by Credit and Human Capital",
                  "decidedBy": "credit2",
                  "decidedAt": "2026-09-30T10:05:11+02:00",
                  "decisionComment": "Matches the signed-off matrix"
                }
              ]
            }""";

    public static final String STAFF_GRADE_LIMIT_APPROVAL = """
            {
              "decision": "APPROVED",
              "comment": "Approved at the annual review"
            }""";

    public static final String STAFF_GRADE_LIMIT_APPROVED = """
            {
              "code": "OK",
              "message": "Grade limit change approved",
              "data": {
                "id": 2,
                "grade": "C4",
                "scoreBand": "Band C",
                "maximumLimit": 350.00,
                "effectiveFrom": "2026-11-01",
                "status": "APPROVED",
                "proposedBy": "credit1",
                "proposedAt": "2026-10-01T08:30:02+02:00",
                "comment": "Annual review approved by Credit and Human Capital",
                "decidedBy": "credit2",
                "decidedAt": "2026-10-01T11:20:45+02:00",
                "decisionComment": "Approved at the annual review"
              }
            }""";

    public static final String STAFF_GRADE_LIMIT_WITHDRAWN = """
            {
              "code": "OK",
              "message": "Grade limit change withdrawn",
              "data": {
                "id": 2,
                "grade": "C4",
                "scoreBand": "Band C",
                "maximumLimit": 350.00,
                "effectiveFrom": "2026-11-01",
                "status": "WITHDRAWN",
                "proposedBy": "credit1",
                "proposedAt": "2026-10-01T08:30:02+02:00",
                "comment": "Annual review approved by Credit and Human Capital",
                "decidedBy": "credit1",
                "decidedAt": "2026-10-01T09:02:17+02:00"
              }
            }""";

    public static final String STAFF_GRADE_LIMIT_CHANGE_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Grade limit change 99 not found"
            }""";

    public static final String STAFF_GRADE_LIMIT_ALREADY_DECIDED = """
            {
              "code": "CONFLICT",
              "message": "Grade limit change 2 is already approved"
            }""";

    public static final String STAFF_GRADE_LIMIT_OWN_CHANGE = """
            {
              "code": "FORBIDDEN",
              "message": "credit1 proposed grade limit change 2 and cannot also approve or reject it; another credit manager or SUPER_ADMIN must"
            }""";

    // ---- Staff Register (FR-SGL-001 to FR-SGL-007) ----
    // hc1 uploads staff-register-2026-10.csv as batch 12: row 2 adds E1043 Tendai Moyo, row 3 moves E1001 Nyasha Dube
    // from C3 to C4, row 4 is refused. hc2 approves it. hc1 then submits batch 13 (E1043 resigned), which hc2 approves.

    private static final String STAFF_ROW_4_REFUSED = """
                {
                  "rowNumber": 4,
                  "outcome": "REJECTED",
                  "values": {
                    "employeeNumber": "E1045",
                    "fullName": "Rudo Chikwanha",
                    "nationalId": "63-1112223-C-07",
                    "mobileNumber": "12345",
                    "grade": "C9",
                    "department": "Operations",
                    "employmentStatus": "Active",
                    "engagementDate": "01/03/2019",
                    "walletAccountNumber": "0782606983"
                  },
                  "errors": {
                    "mobileNumber": "Mobile number must be a Zimbabwean mobile number, e.g. 0772123123 or +263772123123",
                    "grade": "Grade C9 is not in the grade-to-limit matrix"
                  }
                }""";

    public static final String STAFF_REGISTER_UPLOAD_REQUEST = """
            {
              "fileName": "staff-register-2026-10.csv",
              "content": "RW1wbG95ZWUgTnVtYmVyLEZ1bGwgTmFtZSxOYXRpb25hbCBJRCxNb2JpbGUsR3JhZGUsRGVwYXJ0bWVudCxTdGF0dXMsRGF0ZSBKb2luZWQsV2FsbGV0LENvc3QgQ2VudHJlCg==",
              "comment": "October register from Human Capital"
            }""";

    public static final String STAFF_REGISTER_UPLOADED = """
            {
              "code": "CREATED",
              "message": "Staff register file submitted; it reaches the register once someone else approves it",
              "data": {
                "id": 12,
                "source": "UPLOAD",
                "fileName": "staff-register-2026-10.csv",
                "status": "PENDING",
                "submittedBy": "hc1",
                "submittedAt": "2026-10-01T10:15:00+02:00",
                "comment": "October register from Human Capital",
                "totalRows": 3,
                "stagedRows": 2,
                "rejectedRows": 1,
                "ignoredColumns": ["Cost Centre"],
                "rejected": [
            """ + STAFF_ROW_4_REFUSED + """

                ]
              }
            }""";

    public static final String STAFF_REGISTER_NOTHING_TO_LOAD = """
            {
              "code": "NOTHING_TO_LOAD",
              "message": "No row of staff-register-2026-10.csv can be loaded; all 1 were refused",
              "data": {
                "rejected": [
            """ + STAFF_ROW_4_REFUSED + """

                ]
              }
            }""";

    private static final String STAFF_BATCH_12_PENDING = """
                {
                  "id": 12,
                  "source": "UPLOAD",
                  "fileName": "staff-register-2026-10.csv",
                  "status": "PENDING",
                  "submittedBy": "hc1",
                  "submittedAt": "2026-10-01T10:15:00+02:00",
                  "comment": "October register from Human Capital",
                  "totalRows": 3,
                  "stagedRows": 2,
                  "rejectedRows": 1
                }""";

    public static final String STAFF_REGISTER_BATCHES = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
            """ + STAFF_BATCH_12_PENDING + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_REGISTER_BATCH_12 = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + STAFF_BATCH_12_PENDING + """

            }""";

    public static final String STAFF_REGISTER_STAGED_ROWS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "rowNumber": 2,
                    "outcome": "STAGED",
                    "action": "CREATE",
                    "values": {
                      "employeeNumber": "E1043",
                      "fullName": "Tendai Moyo",
                      "nationalId": "632345678B42",
                      "mobileNumber": "263771234000",
                      "grade": "C4",
                      "department": "Finance",
                      "employmentStatus": "ACTIVE",
                      "engagementDate": "2015-02-02",
                      "walletAccountNumber": "263771234000"
                    },
                    "changes": [
                      { "field": "employeeNumber", "to": "E1043" },
                      { "field": "fullName", "to": "Tendai Moyo" },
                      { "field": "nationalId", "to": "632345678B42" },
                      { "field": "mobileNumber", "to": "263771234000" },
                      { "field": "grade", "to": "C4" },
                      { "field": "department", "to": "Finance" },
                      { "field": "employmentStatus", "to": "ACTIVE" },
                      { "field": "engagementDate", "to": "2015-02-02" },
                      { "field": "walletAccountNumber", "to": "263771234000" }
                    ]
                  },
                  {
                    "rowNumber": 3,
                    "outcome": "STAGED",
                    "action": "AMEND",
                    "values": {
                      "employeeNumber": "E1001",
                      "fullName": "Nyasha Dube",
                      "nationalId": "08765432F21",
                      "mobileNumber": "263782606983",
                      "grade": "C4",
                      "department": "Credit",
                      "employmentStatus": "ACTIVE",
                      "engagementDate": "2012-06-11",
                      "walletAccountNumber": "263782606983"
                    },
                    "changes": [
                      { "field": "grade", "from": "C3", "to": "C4" }
                    ]
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 2,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_REGISTER_APPROVAL = """
            {
              "decision": "APPROVED",
              "comment": "Checked against the HR master"
            }""";

    public static final String STAFF_REGISTER_APPROVED = """
            {
              "code": "OK",
              "message": "Staff register batch approved: 1 added, 1 changed, 0 unchanged, 0 skipped",
              "data": {
                "id": 12,
                "source": "UPLOAD",
                "fileName": "staff-register-2026-10.csv",
                "status": "APPROVED",
                "submittedBy": "hc1",
                "submittedAt": "2026-10-01T10:15:00+02:00",
                "comment": "October register from Human Capital",
                "totalRows": 3,
                "stagedRows": 2,
                "rejectedRows": 1,
                "decidedBy": "hc2",
                "decidedAt": "2026-10-01T11:02:40+02:00",
                "decisionComment": "Checked against the HR master",
                "createdRows": 1,
                "amendedRows": 1,
                "unchangedRows": 0,
                "skippedRows": 0
              }
            }""";

    public static final String STAFF_RECORD_REQUEST = """
            {
              "employeeNumber": "E1043",
              "fullName": "Tendai Moyo",
              "nationalId": "63-2345678-B-42",
              "mobileNumber": "0771234000",
              "grade": "C4",
              "department": "Finance",
              "employmentStatus": "RESIGNED",
              "engagementDate": "2015-02-02",
              "walletAccountNumber": "0771234000",
              "comment": "Resigned with effect from 30 September"
            }""";

    public static final String STAFF_RECORD_SUBMITTED = """
            {
              "code": "CREATED",
              "message": "Staff record change submitted; it reaches the register once someone else approves it",
              "data": {
                "id": 13,
                "source": "MANUAL",
                "status": "PENDING",
                "submittedBy": "hc1",
                "submittedAt": "2026-10-01T14:30:12+02:00",
                "comment": "Resigned with effect from 30 September",
                "totalRows": 1,
                "stagedRows": 1,
                "rejectedRows": 0
              }
            }""";

    private static final String STAFF_E1001 = """
                {
                  "id": 1,
                  "employeeNumber": "E1001",
                  "fullName": "Nyasha Dube",
                  "nationalId": "08765432F21",
                  "mobileNumber": "263782606983",
                  "grade": "C4",
                  "department": "Credit",
                  "employmentStatus": "ACTIVE",
                  "engagementDate": "2012-06-11",
                  "walletAccountNumber": "263782606983",
                  "statusChangedAt": "2026-09-30T15:40:03+02:00",
                  "createdAt": "2026-09-30T15:40:03+02:00",
                  "updatedAt": "2026-10-01T11:02:40+02:00",
                  "eligible": true,
                  "limit": """ + GRADE_LIMIT_C4_1 + """

                }""";

    private static final String STAFF_E1012 = """
                {
                  "id": 2,
                  "employeeNumber": "E1012",
                  "fullName": "Chipo Banda",
                  "nationalId": "632223334C55",
                  "mobileNumber": "263773456789",
                  "grade": "C4",
                  "department": "Treasury",
                  "employmentStatus": "ACTIVE",
                  "engagementDate": "2017-03-01",
                  "walletAccountNumber": "263773456789",
                  "statusChangedAt": "2026-09-30T15:40:03+02:00",
                  "createdAt": "2026-09-30T15:40:03+02:00",
                  "updatedAt": "2026-09-30T15:40:03+02:00",
                  "eligible": true,
                  "limit": """ + GRADE_LIMIT_C4_1 + """

                }""";

    private static final String STAFF_E1043 = """
                {
                  "id": 7,
                  "employeeNumber": "E1043",
                  "fullName": "Tendai Moyo",
                  "nationalId": "632345678B42",
                  "mobileNumber": "263771234000",
                  "grade": "C4",
                  "department": "Finance",
                  "employmentStatus": "RESIGNED",
                  "engagementDate": "2015-02-02",
                  "walletAccountNumber": "263771234000",
                  "statusChangedAt": "2026-10-01T15:05:51+02:00",
                  "createdAt": "2026-10-01T11:02:40+02:00",
                  "updatedAt": "2026-10-01T15:05:51+02:00",
                  "eligible": false,
                  "ineligibleReason": "Employment status is RESIGNED; only ACTIVE staff may borrow",
                  "limit": """ + GRADE_LIMIT_C4_1 + """

                }""";

    public static final String STAFF_MEMBERS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
            """ + STAFF_E1001 + """
            ,
            """ + STAFF_E1012 + """
            ,
            """ + STAFF_E1043 + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 3,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_MEMBER_E1043 = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + STAFF_E1043 + """

            }""";

    public static final String STAFF_MEMBER_E1043_HISTORY = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                { "field": "employmentStatus", "previousValue": "ACTIVE", "newValue": "RESIGNED", "batchId": 13, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T15:05:51+02:00" },
                { "field": "walletAccountNumber", "newValue": "263771234000", "batchId": 12, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T11:02:40+02:00" },
                { "field": "engagementDate", "newValue": "2015-02-02", "batchId": 12, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T11:02:40+02:00" },
                { "field": "employmentStatus", "newValue": "ACTIVE", "batchId": 12, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T11:02:40+02:00" },
                { "field": "department", "newValue": "Finance", "batchId": 12, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T11:02:40+02:00" },
                { "field": "grade", "newValue": "C4", "batchId": 12, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T11:02:40+02:00" },
                { "field": "mobileNumber", "newValue": "263771234000", "batchId": 12, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T11:02:40+02:00" },
                { "field": "nationalId", "newValue": "632345678B42", "batchId": 12, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T11:02:40+02:00" },
                { "field": "fullName", "newValue": "Tendai Moyo", "batchId": 12, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T11:02:40+02:00" },
                { "field": "employeeNumber", "newValue": "E1043", "batchId": 12, "submittedBy": "hc1", "approvedBy": "hc2", "changedAt": "2026-10-01T11:02:40+02:00" }
              ]
            }""";

    public static final String STAFF_MEMBER_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Employee E9999 is not on the staff register"
            }""";

    public static final String STAFF_BATCH_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Staff register batch 99 not found"
            }""";

    public static final String STAFF_BATCH_ALREADY_DECIDED = """
            {
              "code": "CONFLICT",
              "message": "Staff register batch 12 is already approved"
            }""";

    public static final String STAFF_BATCH_OWN = """
            {
              "code": "FORBIDDEN",
              "message": "hc1 submitted staff register batch 12 and cannot also approve or reject it; someone else in Human Capital or a SUPER_ADMIN must"
            }""";

    // ---- Staff register reconciliation (FR-SGL-008) ----
    // After batch 13, hc1 reconciles the register (E1001 Nyasha Dube and E1012 Chipo Banda ACTIVE, E1043 Tendai Moyo
    // RESIGNED) against payroll-master-2026-10.csv: row 2 E1001 Resigned, row 3 E1043 at C5 and Active, row 4 E1050
    // Farai Ncube (not on the register), rows 5 and 7 E1052 twice, row 6 with no employee number. E1012 is not on it.

    public static final String STAFF_RECONCILIATION_REQUEST = """
            {
              "fileName": "payroll-master-2026-10.csv",
              "content": "RW1wbG95ZWUgTm8uLFN1cm5hbWUsRmlyc3QgTmFtZSxHcmFkZSxEZXBhcnRtZW50LFN0YXR1cyxQYXkgUG9pbnQK",
              "comment": "October payroll master from Human Capital"
            }""";

    private static final String STAFF_RECONCILIATION_1_FIELDS = """
                  "id": 1,
                  "fileName": "payroll-master-2026-10.csv",
                  "runBy": "hc1",
                  "runAt": "2026-10-01T16:20:05+02:00",
                  "comment": "October payroll master from Human Capital",
                  "comparedFields": ["fullName", "grade", "department", "employmentStatus"],
                  "payrollRows": 6,
                  "registerMembers": 3,
                  "matched": 0,
                  "different": 1,
                  "leftOnPayroll": 1,
                  "leftOnPayrollEligible": 1,
                  "notOnRegister": 1,
                  "notOnPayroll": 1,
                  "notOnPayrollEligible": 1,
                  "duplicatesOnPayroll": 1,
                  "unreadableRows": 1,
                  "variances": 6""";

    public static final String STAFF_RECONCILED = """
            {
              "code": "CREATED",
              "message": "Payroll master reconciled: 6 variances",
              "data": {
            """ + STAFF_RECONCILIATION_1_FIELDS + """
            ,
                  "ignoredColumns": ["Pay Point"]
              }
            }""";

    public static final String STAFF_RECONCILIATIONS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
            """ + STAFF_RECONCILIATION_1_FIELDS + """

                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_RECONCILIATION_1 = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
            """ + STAFF_RECONCILIATION_1_FIELDS + """

              }
            }""";

    public static final String STAFF_RECONCILIATION_VARIANCES = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": 1,
                    "kind": "LEFT_ON_PAYROLL",
                    "employeeNumber": "E1001",
                    "fullName": "Nyasha Dube",
                    "rowNumbers": [2],
                    "eligible": true,
                    "differences": [
                      { "field": "employmentStatus", "register": "ACTIVE", "payroll": "RESIGNED" }
                    ]
                  },
                  {
                    "id": 2,
                    "kind": "NOT_ON_PAYROLL",
                    "employeeNumber": "E1012",
                    "fullName": "Chipo Banda",
                    "eligible": true,
                    "register": {
                      "employeeNumber": "E1012",
                      "fullName": "Chipo Banda",
                      "nationalId": "632223334C55",
                      "mobileNumber": "263773456789",
                      "grade": "C4",
                      "department": "Treasury",
                      "employmentStatus": "ACTIVE",
                      "engagementDate": "2017-03-01",
                      "walletAccountNumber": "263773456789"
                    }
                  },
                  {
                    "id": 3,
                    "kind": "DIFFERENT",
                    "employeeNumber": "E1043",
                    "fullName": "Tendai Moyo",
                    "rowNumbers": [3],
                    "differences": [
                      { "field": "grade", "register": "C4", "payroll": "C5", "note": "Grade C5 is not in the grade-to-limit matrix" },
                      { "field": "employmentStatus", "register": "RESIGNED", "payroll": "ACTIVE" }
                    ]
                  },
                  {
                    "id": 4,
                    "kind": "NOT_ON_REGISTER",
                    "employeeNumber": "E1050",
                    "fullName": "Farai Ncube",
                    "rowNumbers": [4],
                    "payroll": {
                      "employeeNumber": "E1050",
                      "fullName": "Farai Ncube",
                      "grade": "C3",
                      "department": "Operations",
                      "employmentStatus": "Active"
                    }
                  },
                  {
                    "id": 5,
                    "kind": "DUPLICATE_ON_PAYROLL",
                    "employeeNumber": "E1052",
                    "fullName": "Rufaro Sibanda",
                    "rowNumbers": [5, 7]
                  },
                  {
                    "id": 6,
                    "kind": "UNREADABLE",
                    "fullName": "Kudzai Phiri",
                    "rowNumbers": [6],
                    "payroll": {
                      "employeeNumber": "",
                      "fullName": "Kudzai Phiri",
                      "grade": "C3",
                      "department": "Operations",
                      "employmentStatus": "Active"
                    },
                    "errors": {
                      "employeeNumber": "Employee number is required"
                    }
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 6,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_RECONCILIATION_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Staff register reconciliation 99 not found"
            }""";

    // ---- Staff Grocery Loan offers (FR-SGL-015 to FR-SGL-018, FR-SGL-024) ----
    // Human Capital corrected the payroll file and reconciliation 2 (2 October) came back clean. The scheduled run on
    // Monday 5 October (run 1) offers E1001 and E1012 their C4 limit; E1043 has resigned. credit1 runs it again at
    // 09:30 (run 2): both already hold this week's offer, so it issues nothing.

    private static final String STAFF_OFFER_RUN_1 = """
                {
                  "id": 1,
                  "cycleStart": "2026-10-05",
                  "trigger": "SCHEDULED",
                  "startedBy": "scheduler",
                  "startedAt": "2026-10-05T08:00:00+02:00",
                  "finishedAt": "2026-10-05T08:00:01+02:00",
                  "status": "COMPLETED",
                  "reconciliationId": 2,
                  "registerMembers": 3,
                  "ineligible": 1,
                  "excludedActiveLoan": 0,
                  "excludedArrears": 0,
                  "excludedByReconciliation": 0,
                  "excludedByOverride": 0,
                  "eligible": 2,
                  "offered": 2,
                  "refreshed": 0,
                  "alreadyOffered": 0,
                  "withdrawn": 0,
                  "expired": 0
                }""";

    private static final String STAFF_OFFER_RUN_2 = """
                {
                  "id": 2,
                  "cycleStart": "2026-10-05",
                  "trigger": "MANUAL",
                  "startedBy": "credit1",
                  "startedAt": "2026-10-05T09:30:12+02:00",
                  "finishedAt": "2026-10-05T09:30:12+02:00",
                  "status": "COMPLETED",
                  "reconciliationId": 2,
                  "registerMembers": 3,
                  "ineligible": 1,
                  "excludedActiveLoan": 0,
                  "excludedArrears": 0,
                  "excludedByReconciliation": 0,
                  "excludedByOverride": 0,
                  "eligible": 2,
                  "offered": 0,
                  "refreshed": 0,
                  "alreadyOffered": 2,
                  "withdrawn": 0,
                  "expired": 0
                }""";

    public static final String STAFF_OFFER_RUN_STARTED = """
            {
              "code": "CREATED",
              "message": "Offer run completed: 0 offered, 0 refreshed, 2 already held this week's offer",
              "data": """ + STAFF_OFFER_RUN_2 + """

            }""";

    public static final String STAFF_OFFER_RUN_REFUSED = """
            {
              "code": "RUN_REFUSED",
              "message": "The staff register was last reconciled against the payroll master on 2026-10-02, 38 days ago; offers need a reconciliation within the last 35 days",
              "data": {
                "id": 3,
                "cycleStart": "2026-11-09",
                "trigger": "MANUAL",
                "startedBy": "credit1",
                "startedAt": "2026-11-09T09:15:03+02:00",
                "finishedAt": "2026-11-09T09:15:03+02:00",
                "status": "REFUSED",
                "reason": "The staff register was last reconciled against the payroll master on 2026-10-02, 38 days ago; offers need a reconciliation within the last 35 days",
                "reconciliationId": 2
              }
            }""";

    public static final String STAFF_OFFER_RUN_IN_PROGRESS = """
            {
              "code": "CONFLICT",
              "message": "An offer run is already in progress; try again once it has finished"
            }""";

    public static final String STAFF_OFFER_RUNS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
            """ + STAFF_OFFER_RUN_2 + """
            ,
            """ + STAFF_OFFER_RUN_1 + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 2,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_OFFER_RUN_1_RESULT = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + STAFF_OFFER_RUN_1 + """

            }""";

    public static final String STAFF_OFFER_RUN_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Staff offer run 99 not found"
            }""";

    public static final String STAFF_OFFER_SCHEDULE = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "runCron": "0 0 8 * * MON",
                "zone": "Africa/Harare",
                "nextScheduledRunAt": "2026-10-12T08:00:00+02:00",
                "validityDays": 7,
                "maxReconciliationAgeDays": 35,
                "lastReconciliationId": 2,
                "lastReconciledAt": "2026-10-02T11:40:00+02:00",
                "reconciliationAgeDays": 3,
                "readyToRun": true,
                "lastRun": """ + STAFF_OFFER_RUN_2 + """

              }
            }""";

    public static final String STAFF_OFFERS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": 2,
                    "employeeNumber": "E1012",
                    "fullName": "Chipo Banda",
                    "grade": "C4",
                    "scoreBand": "Band C",
                    "amount": 300.00,
                    "gradeLimitChangeId": 1,
                    "cycleStart": "2026-10-05",
                    "runId": 1,
                    "status": "ACTIVE",
                    "issuedAt": "2026-10-05T08:00:01+02:00",
                    "expiresAt": "2026-10-12T08:00:01+02:00"
                  },
                  {
                    "id": 1,
                    "employeeNumber": "E1001",
                    "fullName": "Nyasha Dube",
                    "grade": "C4",
                    "scoreBand": "Band C",
                    "amount": 300.00,
                    "gradeLimitChangeId": 1,
                    "cycleStart": "2026-10-05",
                    "runId": 1,
                    "status": "ACTIVE",
                    "issuedAt": "2026-10-05T08:00:01+02:00",
                    "expiresAt": "2026-10-12T08:00:01+02:00"
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 2,
                "totalPages": 1
              }
            }""";

    // ---- Staff limit overrides (FR-SGL-011) ----
    // credit1 proposes override 1: E1012 Chipo Banda (C4, grade limit 300.00) may borrow at most 150.00 while a salary
    // advance is outstanding. credit2 approves it; from the next weekly run E1012's offer is for 150.00.

    public static final String STAFF_LIMIT_OVERRIDE_PROPOSAL = """
            {
              "employeeNumber": "E1012",
              "amount": 150.00,
              "reason": "Existing salary advance outstanding until December"
            }""";

    public static final String STAFF_LIMIT_OVERRIDE_PROPOSED = """
            {
              "code": "CREATED",
              "message": "Limit override proposed; it applies once another credit manager approves it",
              "data": {
                "id": 1,
                "employeeNumber": "E1012",
                "fullName": "Chipo Banda",
                "grade": "C4",
                "amount": 150.00,
                "reason": "Existing salary advance outstanding until December",
                "status": "PENDING",
                "proposedBy": "credit1",
                "proposedAt": "2026-10-06T09:12:30+02:00"
              }
            }""";

    public static final String STAFF_LIMIT_OVERRIDE_APPROVAL = """
            {
              "decision": "APPROVED",
              "comment": "Confirmed with Payroll"
            }""";

    private static final String STAFF_LIMIT_OVERRIDE_1_APPROVED = """
                {
                  "id": 1,
                  "employeeNumber": "E1012",
                  "fullName": "Chipo Banda",
                  "grade": "C4",
                  "amount": 150.00,
                  "reason": "Existing salary advance outstanding until December",
                  "status": "APPROVED",
                  "proposedBy": "credit1",
                  "proposedAt": "2026-10-06T09:12:30+02:00",
                  "decidedBy": "credit2",
                  "decidedAt": "2026-10-06T10:01:44+02:00",
                  "decisionComment": "Confirmed with Payroll",
                  "inForce": true
                }""";

    public static final String STAFF_LIMIT_OVERRIDE_APPROVED = """
            {
              "code": "OK",
              "message": "Limit override approved; it sets the employee's offer from the next weekly run",
              "data": """ + STAFF_LIMIT_OVERRIDE_1_APPROVED + """

            }""";

    public static final String STAFF_LIMIT_OVERRIDES = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
            """ + STAFF_LIMIT_OVERRIDE_1_APPROVED + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 1,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_LIMIT_OVERRIDE_REVOCATION = """
            {
              "reason": "Salary advance repaid"
            }""";

    public static final String STAFF_LIMIT_OVERRIDE_REVOKED = """
            {
              "code": "OK",
              "message": "Limit override revoked; the employee's grade limit applies from the next weekly run",
              "data": {
                "id": 1,
                "employeeNumber": "E1012",
                "fullName": "Chipo Banda",
                "grade": "C4",
                "amount": 150.00,
                "reason": "Existing salary advance outstanding until December",
                "status": "REVOKED",
                "proposedBy": "credit1",
                "proposedAt": "2026-10-06T09:12:30+02:00",
                "decidedBy": "credit2",
                "decidedAt": "2026-10-06T10:01:44+02:00",
                "decisionComment": "Confirmed with Payroll",
                "revokedBy": "credit1",
                "revokedAt": "2026-12-01T08:40:02+02:00",
                "revocationReason": "Salary advance repaid"
              }
            }""";

    public static final String STAFF_LIMIT_OVERRIDE_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Limit override 99 not found"
            }""";

    public static final String STAFF_LIMIT_OVERRIDE_ALREADY_DECIDED = """
            {
              "code": "CONFLICT",
              "message": "Limit override 1 is already approved"
            }""";

    public static final String STAFF_LIMIT_OVERRIDE_OWN = """
            {
              "code": "FORBIDDEN",
              "message": "credit1 proposed limit override 1 and cannot also approve or reject it; another credit manager or SUPER_ADMIN must"
            }""";
    // Staff notifications (FR-SGL-019 to 023). credit1 announced the launch on 2 Oct to the 1240 members of the register
    // who have not left (broadcast 1, notifications 1 to 1240); run 1 on 5 Oct then made offers 1 (E1001) and 2 (E1012),
    // notified as 1241 and 1242. E1001's SMS went through; E1012's was refused and WhatsApp took it.
    private static final String NOTIFICATION_OFFER_TEXT_300 = "InnBucks Staff Grocery Loan. You have a new offer of up to"
            + " USD 300.00, open until 08.00 on 12 Oct 2026. Log in to the InnBucks app to accept it.";

    private static final String DISPATCHES_1242 = """
                      {
                        "id": 2495,
                        "notificationId": 1242,
                        "employeeNumber": "E1012",
                        "fullName": "Chipo Banda",
                        "channel": "IN_APP",
                        "recipient": "+263773456789",
                        "template": "OFFER_NEW",
                        "templateVersion": 1,
                        "status": "STORED",
                        "gatewayReference": null,
                        "failureReason": null,
                        "attemptedAt": "2026-10-05T08:00:01+02:00"
                      },
                      {
                        "id": 2497,
                        "notificationId": 1242,
                        "employeeNumber": "E1012",
                        "fullName": "Chipo Banda",
                        "channel": "SMS",
                        "recipient": "+263773456789",
                        "template": "OFFER_NEW",
                        "templateVersion": 1,
                        "status": "FAILED",
                        "gatewayReference": "LOANS-SMS-0d5e6f7a-8b9c-4d1e-a2f3-4b5c6d7e8f90",
                        "failureReason": "Notification API rejected SMS: HTTP 400",
                        "attemptedAt": "2026-10-05T08:00:03+02:00"
                      },
                      {
                        "id": 2498,
                        "notificationId": 1242,
                        "employeeNumber": "E1012",
                        "fullName": "Chipo Banda",
                        "channel": "WHATSAPP",
                        "recipient": "+263773456789",
                        "template": "OFFER_NEW",
                        "templateVersion": 1,
                        "status": "SENT",
                        "gatewayReference": null,
                        "failureReason": null,
                        "attemptedAt": "2026-10-05T08:00:04+02:00"
                      }""";

    private static final String DISPATCHES_1241 = """
                      {
                        "id": 2494,
                        "notificationId": 1241,
                        "employeeNumber": "E1001",
                        "fullName": "Nyasha Dube",
                        "channel": "IN_APP",
                        "recipient": "+263782606983",
                        "template": "OFFER_NEW",
                        "templateVersion": 1,
                        "status": "STORED",
                        "gatewayReference": null,
                        "failureReason": null,
                        "attemptedAt": "2026-10-05T08:00:01+02:00"
                      },
                      {
                        "id": 2496,
                        "notificationId": 1241,
                        "employeeNumber": "E1001",
                        "fullName": "Nyasha Dube",
                        "channel": "SMS",
                        "recipient": "+263782606983",
                        "template": "OFFER_NEW",
                        "templateVersion": 1,
                        "status": "SENT",
                        "gatewayReference": "LOANS-SMS-6f1c2a9e-0b7d-4f43-9a51-3c2e8d7b1f04",
                        "failureReason": null,
                        "attemptedAt": "2026-10-05T08:00:02+02:00"
                      }""";

    public static final String STAFF_NOTIFICATIONS = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [
                  {
                    "id": 1242,
                    "employeeNumber": "E1012",
                    "fullName": "Chipo Banda",
                    "template": "OFFER_NEW",
                    "templateVersion": 1,
                    "offerId": 2,
                    "runId": 1,
                    "broadcastId": null,
                    "title": "Your Staff Grocery Loan offer",
                    "message": \"""" + NOTIFICATION_OFFER_TEXT_300 + """
            ",
                    "createdAt": "2026-10-05T08:00:01+02:00",
                    "outboundStatus": "SENT",
                    "skipReason": null,
                    "deliveredChannel": "WHATSAPP",
                    "finishedAt": "2026-10-05T08:00:04+02:00",
                    "dispatches": [""" + DISPATCHES_1242 + """

                    ]
                  },
                  {
                    "id": 1241,
                    "employeeNumber": "E1001",
                    "fullName": "Nyasha Dube",
                    "template": "OFFER_NEW",
                    "templateVersion": 1,
                    "offerId": 1,
                    "runId": 1,
                    "broadcastId": null,
                    "title": "Your Staff Grocery Loan offer",
                    "message": \"""" + NOTIFICATION_OFFER_TEXT_300 + """
            ",
                    "createdAt": "2026-10-05T08:00:01+02:00",
                    "outboundStatus": "SENT",
                    "skipReason": null,
                    "deliveredChannel": "SMS",
                    "finishedAt": "2026-10-05T08:00:02+02:00",
                    "dispatches": [""" + DISPATCHES_1241 + """

                    ]
                  }
                ],
                "page": 0,
                "size": 20,
                "totalItems": 2,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_NOTIFICATION_DISPATCHES = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "items": [""" + DISPATCHES_1242 + "," + DISPATCHES_1241 + """

                ],
                "page": 0,
                "size": 20,
                "totalItems": 5,
                "totalPages": 1
              }
            }""";

    public static final String STAFF_NOTIFICATION_RUN_SUMMARY = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "notifications": 2,
                "outboundStatus": {
                  "PENDING": 0,
                  "SENDING": 0,
                  "SENT": 2,
                  "FAILED": 0,
                  "SKIPPED": 0
                },
                "skipped": {
                  "OPTED_OUT": 0,
                  "FREQUENCY_CAP": 0,
                  "OFFER_CLOSED": 0
                },
                "sentBy": {
                  "SMS": 1,
                  "WHATSAPP": 1
                }
              }
            }""";

    private static final String LAUNCH_TEXT = "InnBucks has launched the Staff Grocery Loan for staff. Log in to the InnBucks"
            + " app to find out more.";

    public static final String STAFF_LAUNCH_PREVIEW = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "template": "LAUNCH",
                "templateVersion": 1,
                "title": "Introducing the Staff Grocery Loan",
                "message": \"""" + LAUNCH_TEXT + """
            ",
                "recipients": 1240,
                "optedOut": 3,
                "leftExcluded": 17,
                "alreadySentAs": null
              }
            }""";

    public static final String STAFF_LAUNCH_REQUEST = """
            {
              "expectedRecipients": 1240
            }""";

    public static final String STAFF_LAUNCH_SENT = """
            {
              "code": "CREATED",
              "message": "Launch announced to 1240 staff: stored in-app now, and sent to their phones at a measured pace",
              "data": {
                "id": 1,
                "kind": "LAUNCH",
                "template": "LAUNCH",
                "templateVersion": 1,
                "title": "Introducing the Staff Grocery Loan",
                "message": \"""" + LAUNCH_TEXT + """
            ",
                "recipients": 1240,
                "leftExcluded": 17,
                "createdBy": "credit1",
                "createdAt": "2026-10-02T09:15:04+02:00",
                "summary": {
                  "notifications": 1240,
                  "outboundStatus": {
                    "PENDING": 1240,
                    "SENDING": 0,
                    "SENT": 0,
                    "FAILED": 0,
                    "SKIPPED": 0
                  },
                  "skipped": {
                    "OPTED_OUT": 0,
                    "FREQUENCY_CAP": 0,
                    "OFFER_CLOSED": 0
                  },
                  "sentBy": {
                    "SMS": 0,
                    "WHATSAPP": 0
                  }
                }
              }
            }""";

    public static final String STAFF_BROADCASTS = """
            {
              "code": "OK",
              "message": "Success",
              "data": [
                {
                  "id": 1,
                  "kind": "LAUNCH",
                  "template": "LAUNCH",
                  "templateVersion": 1,
                  "title": "Introducing the Staff Grocery Loan",
                  "message": \"""" + LAUNCH_TEXT + """
            ",
                  "recipients": 1240,
                  "leftExcluded": 17,
                  "createdBy": "credit1",
                  "createdAt": "2026-10-02T09:15:04+02:00",
                  "summary": {
                    "notifications": 1240,
                    "outboundStatus": {
                      "PENDING": 0,
                      "SENDING": 0,
                      "SENT": 1235,
                      "FAILED": 2,
                      "SKIPPED": 3
                    },
                    "skipped": {
                      "OPTED_OUT": 3,
                      "FREQUENCY_CAP": 0,
                      "OFFER_CLOSED": 0
                    },
                    "sentBy": {
                      "SMS": 1221,
                      "WHATSAPP": 14
                    }
                  }
                }
              ]
            }""";

    public static final String STAFF_LAUNCH_ALREADY_SENT = """
            {
              "code": "CONFLICT",
              "message": "The launch was announced by credit1 (broadcast 1); it is sent once"
            }""";

    public static final String STAFF_OFFER_MESSAGES_REQUEST = """
            {
              "optedOut": true,
              "reason": "Asked by phone to the Human Capital help desk, 1 Oct 2026"
            }""";

    public static final String STAFF_OFFER_MESSAGES_OPTED_OUT = """
            {
              "code": "OK",
              "message": "Employee E1043 will no longer be sent offer messages; offers still appear in the app",
              "data": {
                "employeeNumber": "E1043",
                "fullName": "Tendai Moyo",
                "optedOut": true,
                "reason": "Asked by phone to the Human Capital help desk, 1 Oct 2026",
                "updatedBy": "hc1",
                "updatedAt": "2026-10-01T10:20:31+02:00"
              }
            }""";

    public static final String STAFF_OFFER_MESSAGES = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "employeeNumber": "E1043",
                "fullName": "Tendai Moyo",
                "optedOut": true,
                "reason": "Asked by phone to the Human Capital help desk, 1 Oct 2026",
                "updatedBy": "hc1",
                "updatedAt": "2026-10-01T10:20:31+02:00"
              }
            }""";
}
