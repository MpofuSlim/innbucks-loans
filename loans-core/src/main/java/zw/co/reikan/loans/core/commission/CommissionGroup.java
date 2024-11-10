package zw.co.reikan.loans.core.commission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;
import zw.co.reikan.loans.core.loan.BaseEntity;

import java.math.BigDecimal;

@EqualsAndHashCode(callSuper = true)
@Data
@Table(name = "commission_group")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class CommissionGroup extends BaseEntity {
    @Column(name = "name", unique = true)
    private String name;
    private BigDecimal agentCommission;
    private BigDecimal providerCommission;
    private boolean percentage;
    private boolean enabled;
}
