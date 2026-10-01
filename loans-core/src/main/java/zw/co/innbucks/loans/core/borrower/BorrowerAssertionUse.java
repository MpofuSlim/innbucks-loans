package zw.co.innbucks.loans.core.borrower;

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
import lombok.ToString;

import java.time.LocalDateTime;

/** A middleware assertion that has been used, so it is never accepted again. */
@Entity
@Table(name = "borrower_assertion_uses")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class BorrowerAssertionUse {

    /** What the assertion was used for. */
    public enum Purpose { SIGN_IN, STEP_UP }

    @Id
    @Column(name = "jti", length = 128, nullable = false)
    private String jti;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", length = 16, nullable = false)
    private Purpose purpose;

    @Column(name = "staff_member_id")
    private Long staffMemberId;

    @Column(name = "used_at", nullable = false)
    private LocalDateTime usedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
