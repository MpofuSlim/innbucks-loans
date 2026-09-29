package zw.co.innbucks.loans.core.loan;

import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.audit.AuditService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static zw.co.innbucks.loans.core.merchant.MerchantService.maskAccountNumber;

/**
 * The loan data a credit action was based on (FR-PBL-032), written into the decision log as JSON with
 * its SHA-256. It holds what Credit assesses: the terms, the employment and payslip figures, where
 * the money would go, and which document images were on file. The images themselves are pinned by
 * their SHA-256 rather than copied, so the log can prove which payslip was reviewed without holding
 * a second copy of it; the national ID number, mobile numbers and addresses are left on the loan.
 *
 * <p>Components serialise in declaration order, so the same loan always yields the same JSON and
 * therefore the same hash.
 */
public record CreditDecisionSnapshot(
        String reference,
        String ecNumber,
        String firstName,
        String lastName,
        String merchantCode,
        String originator,
        LoanApprovalStatus ssbApprovalStatus,
        String ssbDeductionId,
        String batchNumber,
        BigDecimal principal,
        BigDecimal disbursedAmount,
        int tenor,
        BigDecimal interestRate,
        BigDecimal feeRate,
        BigDecimal monthlyInstallment,
        BigDecimal grossedMonthlyDeduction,
        Employment employment,
        List<Deduction> payslipDeductions,
        DisbursementType payoutType,
        String payoutAccount,
        Documents documents) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The employment and salary figures, as captured at application. */
    public record Employment(String employerName, String ministry, String station, String grade,
                             ContractType contractType, LocalDate dateOfEngagement,
                             BigDecimal grossSalary, BigDecimal netSalary) {
    }

    public record Deduction(String beneficiary, BigDecimal amount) {
    }

    /** SHA-256 of each stored document, null when none is on file. */
    public record Documents(String payslipPictureSha256, String nationalIdPictureSha256, String signatureSha256) {
    }

    public static CreditDecisionSnapshot of(Loan loan) {
        EmploymentDetail employment = loan.getEmploymentDetail();
        PayoutDestination payee = PayoutDestination.of(loan);
        return new CreditDecisionSnapshot(
                loan.getReference(),
                loan.getEcNumber(),
                loan.getFirstName(),
                loan.getLastName(),
                loan.getMerchant() == null ? null : loan.getMerchant().getMerchantCode(),
                loan.getCreatedBy(),
                loan.getLoanApprovalStatus(),
                loan.getApprovalReference(),
                loan.getBatchNumber(),
                loan.getPrincipal(),
                loan.getDisbursedAmount(),
                loan.getTenor(),
                loan.getInterestRate(),
                loan.getFeeRate(),
                loan.getMonthlyInstallment(),
                loan.getGrossedMonthlyDeduction(),
                employment == null ? null : new Employment(employment.getEmployerName(), employment.getMinistry(),
                        employment.getStation(), employment.getGrade(), employment.getContractType(),
                        employment.getEmploymentStartDate(), employment.getGrossSalary(), employment.getNetSalary()),
                loan.getPayslipDeductions() == null ? List.of() : loan.getPayslipDeductions().stream()
                        .map(d -> new Deduction(d.getBeneficiary(), d.getAmount())).toList(),
                payee.type(),
                maskAccountNumber(payee.paysMerchant() ? payee.merchantAccount() : loan.payoutWalletNumber()),
                new Documents(AuditService.sha256Hex(loan.getPayslipPicture()),
                        AuditService.sha256Hex(loan.getNationalIdPicture()),
                        AuditService.sha256Hex(loan.getSignature())));
    }

    public String toJson() {
        return JSON.writeValueAsString(this);
    }
}
