package zw.co.innbucks.loans.core.user;


import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.loan.BaseEntity;
import zw.co.innbucks.loans.core.merchant.Merchant;

import java.time.LocalDateTime;
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
     * BCrypt-hashed password; tokens are self-issued (see
     * {@code zw.co.innbucks.loans.core.auth}). Nullable so older rows survive the
     * {@code ddl-auto: update} migration — a user with no hash cannot log in.
     */
    @ToString.Exclude
    @Column
    private String password;

    /**
     * Stable per-user identifier used in API paths and JWT {@code sub}: a locally
     * generated UUID (older rows keep the identifier they were created with).
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

    @ToString.Exclude
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

    @ManyToOne
    private CommissionGroup commissionGroup;

    @Column(name = "physical_addess")
    private String physicalAddress;

    /** Consecutive failed sign-ins, reset by a successful one. Null on rows from before lockout existed: 0. */
    @Column(name = "failed_login_attempts")
    private Integer failedLoginAttempts;

    /** Sign-in is refused until this UTC time after too many failed attempts; null when not locked. */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /**
     * Bumped whenever the password is set, and carried in every token as {@code token_version}: a token
     * minted before the last password change no longer matches and is refused. Null reads as 0.
     */
    @Column(name = "token_version")
    private Long tokenVersion;

    /** Invalidates every token minted before now. Saved by the caller. */
    public void bumpTokenVersion() {
        this.tokenVersion = currentTokenVersion() + 1;
    }

    public long currentTokenVersion() {
        return tokenVersion == null ? 0 : tokenVersion;
    }


}
