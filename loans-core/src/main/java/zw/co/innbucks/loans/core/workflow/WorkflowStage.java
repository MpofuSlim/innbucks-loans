package zw.co.innbucks.loans.core.workflow;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One stage of the workflow as an administrator has configured it (FR-SSB-014): its name, who may see, work and
 * assign its queue, its service level and escalation rule, and whether its items are assigned. SUPER_ADMIN holds
 * every entitlement at every stage whatever is stored, so no configuration can lock the platform out of its own work.
 */
@Entity
@Table(name = "workflow_stages")
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStage {

    @Id
    @Column(name = "code", length = 40)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private StageKind kind;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment", length = 16, nullable = false)
    private AssignmentMode assignment;

    /** Hours within which an item should leave the stage; later is overdue. */
    @Column(name = "target_hours", nullable = false)
    private int targetHours;

    /** Hours after which an item still waiting is escalated, once; null if the stage is never escalated. */
    @Column(name = "escalation_hours")
    private Integer escalationHours;

    /** Whether an escalation is also emailed to the item's assignee (for MORE_INFORMATION, its originator). */
    @Column(name = "notify_assignee", nullable = false)
    private boolean notifyAssignee;

    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_stage_roles", joinColumns = @JoinColumn(name = "stage_code"))
    private Set<StageRole> roles = new HashSet<>();

    /** Who is emailed when an item here is escalated. */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "workflow_stage_escalation_roles", joinColumns = @JoinColumn(name = "stage_code"))
    @Column(name = "user_group", length = 32)
    private Set<UserGroup> escalationRoles = new HashSet<>();

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Whether someone holding these roles has the entitlement here. SUPER_ADMIN always does; working or assigning
     * includes seeing the queue.
     */
    public boolean grants(Collection<UserGroup> groups, Entitlement entitlement) {
        if (groups.contains(UserGroup.SUPER_ADMIN)) {
            return true;
        }
        return roles.stream().anyMatch(role -> groups.contains(role.getUserGroup())
                && (role.getEntitlement() == entitlement || entitlement == Entitlement.VIEW));
    }

    /** The roles stored with the entitlement, SUPER_ADMIN aside. */
    public Set<UserGroup> rolesWith(Entitlement entitlement) {
        return roles.stream()
                .filter(role -> role.getEntitlement() == entitlement)
                .map(StageRole::getUserGroup)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(UserGroup.class)));
    }
}
