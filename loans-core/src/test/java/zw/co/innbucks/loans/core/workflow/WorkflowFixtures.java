package zw.co.innbucks.loans.core.workflow;

import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** Stages as seeded, and the loans and users the workflow tests move through them. */
final class WorkflowFixtures {

    static final LocalDateTime SEEDED_AT = LocalDateTime.of(2026, 9, 30, 10, 0);

    private WorkflowFixtures() {
    }

    /** The CREDIT_DECISION stage as seeded: Credit managers see, work and assign it; 24 hours, escalated after 48. */
    static WorkflowStage creditDecision(AssignmentMode assignment) {
        return stage("CREDIT_DECISION", "Credit decision", assignment, 24, 48,
                new StageRole(UserGroup.CREDIT_MANAGER, Entitlement.VIEW),
                new StageRole(UserGroup.CREDIT_MANAGER, Entitlement.WORK),
                new StageRole(UserGroup.CREDIT_MANAGER, Entitlement.ASSIGN));
    }

    /** MORE_INFORMATION as seeded: worked by the originator, so never assigned; Credit managers see it. */
    static WorkflowStage moreInformation() {
        return stage("MORE_INFORMATION", "More information", AssignmentMode.NONE, 48, 96,
                new StageRole(UserGroup.CREDIT_MANAGER, Entitlement.VIEW));
    }

    static WorkflowStage stage(String code, String name, AssignmentMode assignment, int targetHours,
                               Integer escalationHours, StageRole... roles) {
        return WorkflowStage.builder().code(code).kind(StageKind.SYSTEM).name(name).displayOrder(20)
                .assignment(assignment).targetHours(targetHours).escalationHours(escalationHours).notifyAssignee(true)
                .roles(new HashSet<>(Arrays.asList(roles)))
                .escalationRoles(new HashSet<>(Set.of(UserGroup.SUPER_ADMIN)))
                .updatedBy("system").updatedAt(SEEDED_AT).build();
    }

    static User user(String username, UserGroup... groups) {
        User user = new User();
        user.setUsername(username);
        user.setGroups(groups.length == 0 ? EnumSet.noneOf(UserGroup.class) : EnumSet.copyOf(Arrays.asList(groups)));
        return user;
    }

    static Loan loan(long id, String createdBy) {
        Loan loan = new Loan();
        loan.setId(id);
        loan.setCreatedBy(createdBy);
        // Loan's equality ignores its id, so each fixture loan is made distinct by its EC number.
        loan.setEcNumber(String.format("%07dA", id));
        loan.setFirstName("Rudo");
        loan.setLastName("Chikwanha");
        loan.setNationalIdNumber("631234567A42");
        loan.setMobileNumber("263771234567");
        return loan;
    }
}
