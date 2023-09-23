package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.nanoloansweb.loan.BaseEntity;

import javax.persistence.Entity;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Table(name = "loan_approval_batch")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class LoanApprovalBatch extends BaseEntity {
    private String status;
    private BigDecimal totalAmount;
    private LocalDateTime approvalSubmissionDate;
    private String approvalStatus;
    private String approvalMessage;

}
