package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Who holds what at a stage (FR-SSB-014). */
class WorkflowStageTest {

    private final WorkflowStage deductionCancellation = WorkflowFixtures.stage("DEDUCTION_CANCELLATION",
            "Deduction cancellation", AssignmentMode.OPTIONAL, 24, 48,
            new StageRole(UserGroup.CREDIT_MANAGER, Entitlement.VIEW),
            new StageRole(UserGroup.FINANCE, Entitlement.WORK));

    @Test
    @DisplayName("a role holds what it is given, and working a stage includes seeing it")
    void rolesHoldWhatTheyAreGiven() {
        assertThat(deductionCancellation.grants(Set.of(UserGroup.FINANCE), Entitlement.WORK)).isTrue();
        assertThat(deductionCancellation.grants(Set.of(UserGroup.FINANCE), Entitlement.VIEW)).isTrue();
        assertThat(deductionCancellation.grants(Set.of(UserGroup.FINANCE), Entitlement.ASSIGN)).isFalse();
        assertThat(deductionCancellation.grants(Set.of(UserGroup.CREDIT_MANAGER), Entitlement.VIEW)).isTrue();
        assertThat(deductionCancellation.grants(Set.of(UserGroup.CREDIT_MANAGER), Entitlement.WORK)).isFalse();
    }

    @Test
    @DisplayName("SUPER_ADMIN holds everything whatever is stored; AGENTS and the role-less nothing")
    void superAdminAlwaysAgentsNever() {
        for (Entitlement entitlement : Entitlement.values()) {
            assertThat(deductionCancellation.grants(Set.of(UserGroup.SUPER_ADMIN), entitlement)).isTrue();
            assertThat(deductionCancellation.grants(Set.of(UserGroup.AGENTS), entitlement)).isFalse();
            assertThat(deductionCancellation.grants(Set.of(), entitlement)).isFalse();
        }
    }

    @Test
    @DisplayName("the roles stored with an entitlement leave SUPER_ADMIN out")
    void rolesWith() {
        assertThat(deductionCancellation.rolesWith(Entitlement.WORK)).containsExactly(UserGroup.FINANCE);
        assertThat(deductionCancellation.rolesWith(Entitlement.ASSIGN)).isEmpty();
    }
}
