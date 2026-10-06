package zw.co.innbucks.loans.core.loan;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "loan_disbursements")
@Data
public class LoanDisbursement extends BaseEntity {

    /** LAZY: nothing reads an attempt's loan through it; the payout code holds the loan it locked. */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
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
