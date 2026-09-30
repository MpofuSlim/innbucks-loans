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
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.math.BigDecimal;
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
 *
 * <p>A {@link StageKind#CHECKPOINT} is a stage an administrator added: it holds each loan it applies to at its
 * {@link HoldPoint} until someone who works it clears or declines the loan.
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

    /** Where a checkpoint holds loans; null for a system stage. */
    @Enumerated(EnumType.STRING)
    @Column(name = "hold_point", length = 32)
    private HoldPoint holdPoint;

    /** A checkpoint that applies only to loans of at least this principal; null for every amount. */
    @Column(name = "minimum_principal", precision = 19, scale = 2)
    private BigDecimal minimumPrincipal;

    /**
     * The channels a checkpoint applies to, by channel id ({@value #PORTAL} for applications with no channel); empty
     * for every channel.
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_stage_channels", joinColumns = @JoinColumn(name = "stage_code"))
    @Column(name = "channel_id")
    private Set<String> channels = new HashSet<>();

    /** Whether the stage is in use. A system stage always is; a deactivated checkpoint holds nothing. */
    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** When a checkpoint last became active: a loan already at its point waits from then. Null for a system stage. */
    @Column(name = "active_since")
    private LocalDateTime activeSince;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** The channel key of an application captured on the portal, with no channel. */
    public static final String PORTAL = "PORTAL";

    public boolean isCheckpoint() {
        return kind == StageKind.CHECKPOINT;
    }

    /**
     * Whether its items can be given to a person: every checkpoint, and each system stage not worked by the originator.
     */
    public boolean assignable() {
        return SystemStage.of(code).map(SystemStage::assignable).orElse(isCheckpoint());
    }

    /**
     * Whether its decision is barred to whoever originated the loan or is a party to it (FR-PBL-029): every checkpoint,
     * and the system stages that decide an application.
     */
    public boolean segregated() {
        return SystemStage.of(code).map(SystemStage::segregated).orElse(isCheckpoint());
    }

    /**
     * Whether its decision is also barred to whoever approved the loan at Credit: a checkpoint a loan reaches only
     * once approved, such as the payout authorisation (FR-SSB-018), is a check separate from that approval.
     */
    public boolean barsCreditApprover() {
        return isCheckpoint() && holdPoint != null && holdPoint.followsCreditApproval();
    }

    /**
     * Whether this checkpoint applies to the loan, by its principal and channel; always false for a system stage.
     * Whether the loan is at the checkpoint's point is a separate question.
     */
    public boolean appliesTo(Loan loan) {
        if (!isCheckpoint()) {
            return false;
        }
        if (minimumPrincipal != null
                && (loan.getPrincipal() == null || loan.getPrincipal().compareTo(minimumPrincipal) < 0)) {
            return false;
        }
        return channels.isEmpty() || channels.contains(channelOf(loan));
    }

    /** The loan's channel id, or {@value #PORTAL} when it has none. */
    public static String channelOf(Loan loan) {
        Channel channel = loan.getChannel();
        return channel == null || channel.getChannelId() == null ? PORTAL : channel.getChannelId();
    }

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
