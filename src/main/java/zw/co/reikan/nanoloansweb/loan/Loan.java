package zw.co.reikan.nanoloansweb.loan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Table(name = "loan_request", indexes = {
        @Index(name = "idx_batch_number", columnList = "batch_number"),
        @Index(name = "idx_ec_number", columnList = "ec_number")
})
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Loan extends BaseEntity {

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "disburse_amount")
    private BigDecimal disbursedAmount;

    @Column(name = "interest_rate")
    private BigDecimal interestRate;

    @Column(name = "interest_amount")
    private BigDecimal interestAmount;

    @Column(name = "fee_rate")
    private BigDecimal feeRate;

    @Column(name = "fee_amount")
    private BigDecimal feeAmount;

    @Column(name = "monthly_installment")
    private BigDecimal monthlyInstallment;

    @Column(name = "tenor")
    private int tenor;

    @Column(name = "loan_start_date")
    private LocalDate loanStartDate;

    @Column(name = "loan_end_date")
    private LocalDate loanEndDate;

    @Column(name = "mobile_number")
    private String mobileNumber;

    @Column(name = "ec_number")
    private String ecNumber;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "national_id_number")
    private String nationalIdNumber;

    @Lob
    @Column(name = "signature", columnDefinition = "MEDIUMTEXT")
    private String signature;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "loan_status")
    private LoanApprovalStatus loanApprovalStatus;

    @Column(name = "loan_status_message")
    private String loanStatusMessage;

    @Column(name = "date_approved")
    private LocalDateTime dateApproved;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "disbursement_status")
    private LoanDisbursementStatus disbursementStatus;

    @Column(name = "disbursement_status_message")
    private String disbursementStatusMessage;

    @Column(name = "date_disbursed")
    private LocalDateTime dateDisbursed;

    @Column(name = "disbursement_reference")
    private String disbursementReference;

    @Column(name = "approval_reference")
    private String approvalReference;

    @Column(name = "batch_number")
    private String batchNumber;

    @Column(name = "commission_rate")
    private BigDecimal commissionRate;

    @Column(name = "grossed_monthly_deduction")
    private BigDecimal grossedMonthlyDeduction;

    public String getReference() {
        return String.format("%09d", getId());
    }

}
