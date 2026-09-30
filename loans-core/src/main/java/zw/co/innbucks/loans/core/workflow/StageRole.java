package zw.co.innbucks.loans.core.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.innbucks.loans.core.user.UserGroup;

/** One role's entitlement at a stage. */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StageRole {

    @Enumerated(EnumType.STRING)
    @Column(name = "user_group", length = 32, nullable = false)
    private UserGroup userGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "entitlement", length = 16, nullable = false)
    private Entitlement entitlement;
}
