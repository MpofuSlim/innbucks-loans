package zw.co.innbucks.loans.core.authority;

import java.math.BigDecimal;

/** What an administrator sets on a credit authority level, when adding it or changing it. */
public interface CreditAuthorityLevelSettings {

    String getName();

    BigDecimal getMaximumPrincipal();
}
