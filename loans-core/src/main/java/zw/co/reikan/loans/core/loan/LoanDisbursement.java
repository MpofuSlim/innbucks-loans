package zw.co.reikan.loans.core.loan;

import lombok.Data;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "loan_disbursement")
@Data
public class LoanDisbursement extends BaseEntity {

    @ManyToOne
    private Loan loan;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "disbursement_status")
    private LoanDisbursementStatus disbursementStatus;

    @Column(name = "disbursement_status_message")
    private String disbursementStatusMessage;

    @Column(name = "date_disbursed")
    private LocalDateTime dateDisbursed;

    @Column(name = "disbursement_reference")
    private String disbursementReference;
}
