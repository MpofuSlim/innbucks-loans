package zw.co.reikan.loans.core.user;


import jakarta.persistence.*;
import lombok.Data;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionStructure;
import zw.co.reikan.loans.core.loan.BaseEntity;
import zw.co.reikan.loans.core.merchant.Merchant;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(indexes = {
        @Index(name = "idx_external_system_id", columnList = "externalSystemId")
})
@Data
public class User extends BaseEntity {

    public static final String SYSTEM_USER_NAME = "SYSTEM_USER";

    @Column(nullable = false, length = 100, unique = true)
    private String username;

    /**
     * BCrypt-hashed password. Authentication is now self-issued (see
     * {@code zw.co.reikan.loans.core.auth}); this replaces the credential that
     * previously lived in Keycloak. Nullable so pre-existing rows survive the
     * {@code ddl-auto: update} migration — a user with no hash cannot log in.
     */
    @Column
    private String password;

    /**
     * Stable per-user identifier used in API paths and JWT {@code sub}. Formerly
     * the Keycloak user id; now a locally generated UUID.
     */
    @Column(nullable = false, length = 100, unique = true)
    private String externalSystemId;

    @Column
    private String firstName;

    @Column
    private String lastName;

    @Column
    private String email;

    @Column
    private String mobileNumber;

    @Column
    private String idNumber;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_groups", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "user_group")
    private Set<UserGroup> groups = new HashSet<>();

    @ManyToOne(optional = false)
    private Merchant merchant;

    @Column(nullable = false)
    private Boolean temporaryPassword;

    @ManyToOne(optional = true)
    private User agent;

    @ManyToOne
    private CommissionGroup commissionGroup;

    @Column(name = "physical_addess")
    private String physicalAddress;


}
