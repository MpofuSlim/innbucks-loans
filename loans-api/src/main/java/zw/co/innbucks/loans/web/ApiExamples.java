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
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

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
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

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
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

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
                "merchantCode": "harare-motors",
                "merchantName": "Harare Motor Spares",
            """ + LOAN_APPLICANT + "\n" + LOAN_TERMS + """

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

    /**
     * Loan 42's credit decision log: returned on the June payslip, answered with the August one, then approved by
     * someone other than the answerer. Each entry pins the payslip it was taken on by its fingerprint.
     */
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
                  """ + LOAN_42_SNAPSHOT_JUNE_PAYSLIP_FIELDS + """

                },
                {
                  "id": 18,
                  "action": "RESUBMITTED",
                  "comment": "Confirmed with the school bursar: the August payslip figures match the application",
                  "performedBy": "tmoyo",
                  "performedAt": "2026-09-30T10:03:10+02:00",
                  """ + LOAN_42_SNAPSHOT_AUGUST_PAYSLIP_FIELDS + """

                },
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
}
