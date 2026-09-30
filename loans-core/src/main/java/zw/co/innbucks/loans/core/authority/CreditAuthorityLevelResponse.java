package zw.co.innbucks.loans.core.authority;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A credit authority level as configured, with who holds it.
 *
 * @param maximumPrincipal absent for the level with no limit
 * @param holders          the usernames given this level, alphabetically
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreditAuthorityLevelResponse(
        String code,
        String name,
        BigDecimal maximumPrincipal,
        List<String> holders,
        String updatedBy,
        LocalDateTime updatedAt) {

    static CreditAuthorityLevelResponse of(CreditAuthorityLevel level, List<String> holders) {
        return new CreditAuthorityLevelResponse(level.getCode(), level.getName(), level.getMaximumPrincipal(),
                List.copyOf(holders), level.getUpdatedBy(), level.getUpdatedAt());
    }
}
