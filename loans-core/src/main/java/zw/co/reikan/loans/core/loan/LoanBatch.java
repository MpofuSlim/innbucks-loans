package zw.co.reikan.loans.core.loan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;

@Data
@Table(name = "loan_batch", indexes = {
        @Index(name = "uq_loan_batch_batch_number", columnList = "batch_number", unique = true)
})
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class LoanBatch extends BaseEntity {
    @Column(name = "batch_number")
    private String batchNumber;
}
