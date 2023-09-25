package zw.co.reikan.nanoloansweb.loan;

import lombok.Data;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import java.time.LocalDateTime;

@Embeddable
@Data
public class LoanApproval {
    @Enumerated(value = EnumType.STRING)
    @Column(name = "loan_status")
    private LoanApprovalStatus status;

    @Column(name = "loan_status_message")
    private String message;

    @Column(name = "date_approved")
    private LocalDateTime dateApproved;

    @Column(name = "approval_reference")
    private String approvalReference;

    @Column(name = "batch_id")
    private String batchId;
}
