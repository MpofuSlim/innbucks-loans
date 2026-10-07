package zw.co.innbucks.loans.core.user;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByExternalSystemId(String externalSystemId);

    List<User> findByMerchant_MerchantCode(String merchantCode);

    /** Everyone in a group, such as those an overdue credit decision is escalated to. */
    List<User> findByGroupsContaining(UserGroup group);

    List<User> findByUsernameIn(Collection<String> usernames);

    /**
     * A page of the users whose username contains {@code username}, ignoring case; the page's order comes from the
     * {@link Pageable}. Spring Data renders it {@code upper(username) like upper(?) escape '\'}, which the trigram
     * index {@code idx_users_username_trgm} (V34) is built on: change the one with the other
     * ({@code DashboardAndSearchPostgresIT}).
     */
    List<User> findByUsernameContainingIgnoreCase(String username, Pageable page);

    /** Everyone given a credit authority level. */
    List<User> findByCreditAuthorityLevelIsNotNull();

    /** Everyone at one of these credit authority levels. */
    List<User> findByCreditAuthorityLevelIn(Collection<String> levels);

    boolean existsByCreditAuthorityLevel(String level);

    @Query("SELECT u FROM User u WHERE (:merchantId IS NULL OR u.merchant.id= :merchantId)")
    List<User> listAllUsersByMerchant(@Param("merchantId") Long merchantId);

    /**
     * One atomic increment per failed sign-in. Never read-modify-write through the entity: two wrong
     * passwords racing would each read N and both write N+1, so a concurrent attacker would never reach
     * the limit.
     */
    @Transactional
    @Modifying
    @Query("update User u set u.failedLoginAttempts = coalesce(u.failedLoginAttempts, 0) + 1 where u.id = :id")
    int recordFailedLogin(@Param("id") Long id);

    /**
     * Locks the account once it has reached the limit and is not already locked. Returns 1 only for the
     * call that applied the lock, so a burst of failures locks, and is audited, once.
     */
    @Transactional
    @Modifying
    @Query("""
            update User u set u.lockedUntil = :until
            where u.id = :id and coalesce(u.failedLoginAttempts, 0) >= :max
              and (u.lockedUntil is null or u.lockedUntil <= :now)
            """)
    int lockIfOverLimit(@Param("id") Long id, @Param("max") int max, @Param("until") LocalDateTime until,
                        @Param("now") LocalDateTime now);

    /** A successful sign-in, or an expired lock: the strike count starts again. */
    @Transactional
    @Modifying
    @Query("""
            update User u set u.failedLoginAttempts = 0, u.lockedUntil = null
            where u.id = :id and (coalesce(u.failedLoginAttempts, 0) <> 0 or u.lockedUntil is not null)
            """)
    int clearFailedLogins(@Param("id") Long id);

    /** Read on every authenticated request, so a token minted before a password change is refused. */
    @Query("select coalesce(u.tokenVersion, 0) from User u where u.username = :username")
    Optional<Long> findTokenVersionByUsername(@Param("username") String username);

}
