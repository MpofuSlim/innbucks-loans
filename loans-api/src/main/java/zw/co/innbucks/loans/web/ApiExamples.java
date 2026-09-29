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
            """ + LOAN_DOCUMENTS + """

            }""";
}
