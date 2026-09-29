package zw.co.innbucks.loans.core.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/** One finding that held an application for payslip review, and the other application it involves. */
@Entity
@Immutable
@Table(name = "payslip_fraud_flags")
@Getter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayslipFraudFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 64, nullable = false)
    private PayslipFraudReason reason;

    /** The other application with the same payslip; null for a finding about this application alone. */
    @Column(name = "matched_loan_id")
    private Long matchedLoanId;

    @Column(name = "detail")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
