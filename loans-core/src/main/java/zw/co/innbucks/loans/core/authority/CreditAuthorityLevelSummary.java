package zw.co.innbucks.loans.core.authority;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * A credit authority level named in another view.
 *
 * @param maximumPrincipal absent for the level with no limit
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreditAuthorityLevelSummary(String code, String name, BigDecimal maximumPrincipal) {

    public static CreditAuthorityLevelSummary of(CreditAuthorityLevel level) {
        return level == null ? null
                : new CreditAuthorityLevelSummary(level.getCode(), level.getName(), level.getMaximumPrincipal());
    }
}
