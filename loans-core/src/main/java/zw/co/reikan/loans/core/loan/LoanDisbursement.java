package zw.co.reikan.loans.core.loan;

import lombok.Data;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import java.time.LocalDateTime;

@Embeddable
@Data
public class LoanDisbursement {
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
