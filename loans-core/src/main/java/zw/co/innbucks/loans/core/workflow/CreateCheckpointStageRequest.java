package zw.co.innbucks.loans.core.workflow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.math.BigDecimal;
import java.util.Set;

/**
 * A checkpoint stage to add (FR-SSB-014): where it holds loans, which loans, and the same configuration as any other
 * stage. Its code and point are fixed once created.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCheckpointStageRequest implements StageSettings {

    @NotBlank(message = "Code is required")
    @Pattern(regexp = "[A-Z][A-Z0-9_]{2,39}",
            message = "Code must be 3 to 40 capital letters, digits or underscores, starting with a letter")
    @Schema(description = "The stage's code, used in the queue and decision URLs; fixed once created",
            example = "AGENT_APPLICATION_REVIEW")
    private String code;

    @NotNull(message = "Hold point is required")
    @Schema(description = "Where the checkpoint holds loans: BEFORE_LODGEMENT, BEFORE_CREDIT_APPROVAL or"
            + " BEFORE_BOOKING; fixed once created", example = "BEFORE_LODGEMENT")
    private HoldPoint holdPoint;

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    @Schema(example = "Agent application review")
    private String name;

    @Size(max = 500, message = "Description must be at most 500 characters")
    @Schema(example = "A credit manager checks an application captured on the portal against the originals before it is lodged")
    private String description;

    @Min(value = 1, message = "Display order must be at least 1")
    @Max(value = 1000, message = "Display order must be at most 1000")
    @Schema(description = "Where the stage is listed among the others; omit to list it after the stage it follows"
            + " (15, 25 or 35 by hold point)", example = "15")
    private Integer displayOrder;

    @DecimalMin(value = "0.01", message = "Minimum principal must be more than zero")
    @Digits(integer = 17, fraction = 2, message = "Minimum principal must have at most 2 decimal places")
    @Schema(description = "Hold only loans of at least this principal; omit for every amount", example = "1000.00")
    private BigDecimal minimumPrincipal;

    @Size(max = 20, message = "At most 20 channels")
    @Schema(description = "Hold only applications from these channels (a channel id, or PORTAL for applications with"
            + " no channel); omit or leave empty for every channel", example = "[\"PORTAL\"]")
    private Set<@NotBlank(message = "A channel cannot be blank") String> channels;

    @NotNull(message = "Assignment is required")
    @Schema(description = "NONE, OPTIONAL or EXCLUSIVE", example = "OPTIONAL")
    private AssignmentMode assignment;

    @NotNull(message = "View roles are required")
    @Schema(description = "Who sees the stage's queue, besides SUPER_ADMIN and anyone who works or assigns it",
            example = "[\"CREDIT_MANAGER\"]")
    private Set<UserGroup> viewRoles;

    @NotNull(message = "Work roles are required")
    @Schema(description = "Who clears or declines the stage's loans, besides SUPER_ADMIN", example = "[\"CREDIT_MANAGER\"]")
    private Set<UserGroup> workRoles;

    @NotNull(message = "Assign roles are required")
    @Schema(description = "Who gives the stage's items to others, besides SUPER_ADMIN", example = "[\"CREDIT_MANAGER\"]")
    private Set<UserGroup> assignRoles;

    @NotNull(message = "Target hours is required")
    @Min(value = 1, message = "Target hours must be at least 1")
    @Max(value = 720, message = "Target hours must be at most 720")
    @Schema(description = "Hours within which a loan should leave the stage; later is overdue", example = "8")
    private Integer targetHours;

    @Min(value = 1, message = "Escalation hours must be at least 1")
    @Max(value = 1440, message = "Escalation hours must be at most 1440")
    @Schema(description = "Hours after which a loan still waiting is escalated, at least the target; omit for no"
            + " escalation", example = "24")
    private Integer escalationHours;

    @NotNull(message = "Escalation roles are required")
    @Schema(description = "Who is emailed when a loan is escalated", example = "[\"SUPER_ADMIN\"]")
    private Set<UserGroup> escalateTo;

    @NotNull(message = "Notify assignee is required")
    @Schema(description = "Whether an escalation is also emailed to the item's assignee", example = "true")
    private Boolean notifyAssignee;
}
