package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.nanoloansweb.loan.BaseEntity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Table(name = "loan_approval_batch")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class NdasendaBatch extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "deduction_batch_status")
    private DeductionBatchStatus deductionBatchStatus;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Column(name = "date_committed")
    private LocalDateTime dateCommitted;

    @Column(name = "approval_status")
    private String approvalStatus;

    @Column(name = "batch_id")
    private String batchId;
    
}
