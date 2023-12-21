package zw.co.reikan.loans.core.loan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import javax.persistence.Column;
import javax.persistence.Embedded;
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

    @Column(name = "principal")
    private BigDecimal principal;

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

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

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

    @Column(name = "disbursement_attempts")
    private Integer disbursementAttempts;

    @Column(name = "next_disbursement_attempt_date")
    private LocalDateTime nextDisbursementAttemptDate;

    @Column(name = "approval_reference")
    private String approvalReference;

    @Column(name = "batch_number")
    private String batchNumber;

    @Column(name = "commission_rate")
    private BigDecimal commissionRate;

    @Column(name = "grossed_monthly_deduction")
    private BigDecimal grossedMonthlyDeduction;

    @Column(name = "repayment_start_date")
    private LocalDate repaymentStartDate;

    @Column(name = "repayment_end_date")
    private LocalDate repaymentEndDate;

    @Column(name = "agent_commission_rate")
    private BigDecimal agentCommissionRate;

    @Column(name = "agent_commission")
    private BigDecimal agentCommission;

    @Column(name = "number_of_dependencies")
    private int numberOfDependencies;

    @Enumerated(EnumType.STRING)
    @Column(name = "education_level")
    private EducationLevel educationLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "marital_status")
    private MaritalStatus maritalStatus;

    @Column(name = "alternate_contact_number")
    private String alternateContactNumber;

    @Column(name = "place_of_birth")
    private String placeOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "title")
    private Title title;

    @Column(name = "email")
    private String email;

    @Embedded
    private Address address;

    @Embedded
    private EmploymentDetail employmentDetail;

    @Embedded
    private NextOfKin nextOfKin;

    @Embedded
    private Witness witness;

    public String getReference() {
        return String.format("%09d", getId());
    }

}
