package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A request to write off a Staff Grocery Loan, or to reverse a write-off (FR-GEN-011), under maker-checker: proposed
 * with a reason, decided by someone else.
 */
@Entity
@Table(name = "staff_loan_write_offs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "staffLoanId", "kind", "status"})
public class StaffLoanWriteOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_loan_id", nullable = false, updatable = false)
    private Long staffLoanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 24, nullable = false, updatable = false)
    private StaffLoanWriteOffKind kind;

    /** What the loan owed when it was proposed, as loans knows it. */
    @Column(name = "amount", precision = 19, scale = 2, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffLoanWriteOffStatus status;

    @Column(name = "proposed_by", nullable = false, updatable = false)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false, updatable = false)
    private LocalDateTime proposedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_comment")
    private String decisionComment;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
