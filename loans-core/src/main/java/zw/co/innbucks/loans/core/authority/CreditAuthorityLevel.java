package zw.co.innbucks.loans.core.authority;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A credit approval authority (FR-PBL-028): the largest principal someone at this level may approve. Levels rank by
 * that limit, and the one with none approves any amount. Users are given a level each.
 */
@Entity
@Table(name = "credit_authority_levels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditAuthorityLevel {

    @Id
    @Column(name = "code", length = 40)
    private String code;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    /** The largest principal this level may approve; null for any amount. */
    @Column(name = "maximum_principal", precision = 19, scale = 2)
    private BigDecimal maximumPrincipal;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Whether this level may approve the principal; one not known, only a level with no limit. */
    public boolean covers(BigDecimal principal) {
        return maximumPrincipal == null || (principal != null && principal.compareTo(maximumPrincipal) <= 0);
    }
}
