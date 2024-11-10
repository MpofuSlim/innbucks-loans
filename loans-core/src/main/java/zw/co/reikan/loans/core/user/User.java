package zw.co.reikan.loans.core.user;


import jakarta.persistence.*;
import lombok.Data;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionStructure;
import zw.co.reikan.loans.core.loan.BaseEntity;
import zw.co.reikan.loans.core.merchant.Merchant;

@Entity
@Table(indexes = {
        @Index(name = "idx_external_system_id", columnList = "externalSystemId")
})
@Data
public class User extends BaseEntity {

    public static final String SYSTEM_USER_NAME = "SYSTEM_USER";

    @Column(nullable = false, length = 100, unique = true)
    private String username;

    @Column(nullable = false, length = 100, unique = true)
    private String externalSystemId;

    @ManyToOne(optional = false)
    private Merchant merchant;

    @Column(nullable = false)
    private Boolean temporaryPassword;

    @ManyToOne
    private User agent;

    @ManyToOne
    private CommissionGroup commissionGroup;

}
