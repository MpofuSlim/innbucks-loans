package zw.co.innbucks.loans.core.authority;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CreditAuthorityLevelRepository extends JpaRepository<CreditAuthorityLevel, String> {

    /** Lowest limit first, the level with no limit last. */
    @Query("select l from CreditAuthorityLevel l order by l.maximumPrincipal asc nulls last")
    List<CreditAuthorityLevel> findAllRanked();

    Optional<CreditAuthorityLevel> findByMaximumPrincipal(BigDecimal maximumPrincipal);

    Optional<CreditAuthorityLevel> findByMaximumPrincipalIsNull();
}
