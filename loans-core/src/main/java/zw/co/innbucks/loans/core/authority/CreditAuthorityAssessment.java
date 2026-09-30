package zw.co.innbucks.loans.core.authority;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.loans.core.user.UserGroup;

/**
 * Who may approve a loan's principal, and whether one officer may (FR-PBL-028).
 *
 * @param limitsApply     false while no credit authority level is set up: then anyone who works the credit decision
 *                        may approve any amount, as before limits existed
 * @param requiredLevel   the lowest level that may approve it; absent when limits do not apply or it is above every
 *                        level
 * @param aboveEveryLevel it is above every level, so only SUPER_ADMIN may approve it
 * @param yourLevel       the officer's own level; absent when they have none
 * @param withinYourLimit whether the officer's authority covers it (always, for SUPER_ADMIN)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreditAuthorityAssessment(
        boolean limitsApply,
        CreditAuthorityLevelSummary requiredLevel,
        boolean aboveEveryLevel,
        CreditAuthorityLevelSummary yourLevel,
        @Schema(description = "Whether the officer's approval limit covers the principal; the other approval rules"
                + " (segregation of duties, holds) still apply")
        boolean withinYourLimit) {

    /** Anyone may approve any amount: no level is set up. */
    static CreditAuthorityAssessment unlimited() {
        return new CreditAuthorityAssessment(false, null, false, null, true);
    }

    /** Who a referral goes to: the required level's code, or SUPER_ADMIN when it is above every level. */
    @JsonIgnore
    public String referredTo() {
        return requiredLevel == null ? UserGroup.SUPER_ADMIN.name() : requiredLevel.code();
    }

    /** Who a referral goes to, in words: the required level's name, "Senior credit officer", or SUPER_ADMIN. */
    @JsonIgnore
    public String referredToName() {
        return requiredLevel == null ? UserGroup.SUPER_ADMIN.name() : requiredLevel.name();
    }

    /** Who may approve it, in words: "Senior credit officer or above", or "SUPER_ADMIN". */
    @JsonIgnore
    public String approversDescription() {
        return requiredLevel == null ? UserGroup.SUPER_ADMIN.name() : requiredLevel.name() + " or above";
    }
}
