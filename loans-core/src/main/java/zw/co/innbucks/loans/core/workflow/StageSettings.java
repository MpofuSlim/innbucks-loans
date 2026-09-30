package zw.co.innbucks.loans.core.workflow;

import zw.co.innbucks.loans.core.user.UserGroup;

import java.math.BigDecimal;
import java.util.Set;

/** What a create or an update sets on a stage, so both are validated and applied the same way. */
interface StageSettings {

    String getName();

    String getDescription();

    Integer getDisplayOrder();

    AssignmentMode getAssignment();

    Set<UserGroup> getViewRoles();

    Set<UserGroup> getWorkRoles();

    Set<UserGroup> getAssignRoles();

    Integer getTargetHours();

    Integer getEscalationHours();

    Set<UserGroup> getEscalateTo();

    Boolean getNotifyAssignee();

    BigDecimal getMinimumPrincipal();

    Set<String> getChannels();
}
