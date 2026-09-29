package zw.co.innbucks.loans.core.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * A reason a credit officer can give for a decision (FR-PBL-027). Each code belongs to one decision.
 * The set is maintained by migration; an inactive code can no longer be chosen but still names the
 * decisions recorded with it.
 */
@Entity
@Immutable
@Table(name = "credit_reason_codes")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditReasonCode {

    @Id
    @Column(name = "code", length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 32, nullable = false)
    private InternalApprovalStatus decision;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
