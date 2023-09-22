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
import javax.persistence.Lob;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Table(name = "loan_request")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Loan extends BaseEntity {
    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "mobile_number")
    private String mobileNumber;

    @Column(name = "ec_number")
    private String ecNumber;

    @Lob
    @Column(name = "signature", columnDefinition = "MEDIUMTEXT")
    private String signature;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "loan_status")
    private LoanApprovaStatus loanApprovaStatus;

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

    @Column(name = "internal_reference")
    private String internalReference;

}
