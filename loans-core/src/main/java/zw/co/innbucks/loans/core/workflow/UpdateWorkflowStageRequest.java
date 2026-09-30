package zw.co.innbucks.loans.core.workflow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.math.BigDecimal;
import java.util.Set;

/**
 * A stage's whole configuration, replacing what it had (FR-SSB-014). The minimum principal, channels and active flag
 * are a checkpoint's; a system stage applies to every loan and is always active, so they must be left out for one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateWorkflowStageRequest implements StageSettings {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    @Schema(example = "Credit decision")
    private String name;

    @Size(max = 500, message = "Description must be at most 500 characters")
    @Schema(example = "Approve, reject or return an application SSB has accepted")
    private String description;

    @Min(value = 1, message = "Display order must be at least 1")
    @Max(value = 1000, message = "Display order must be at most 1000")
    @Schema(description = "Where the stage is listed among the others; omit to keep it where it is", example = "20")
    private Integer displayOrder;

    @NotNull(message = "Assignment is required")
    @Schema(description = "NONE, OPTIONAL or EXCLUSIVE", example = "EXCLUSIVE")
    private AssignmentMode assignment;

    @NotNull(message = "View roles are required")
    @Schema(description = "Who sees the stage's queue, besides SUPER_ADMIN and anyone who works or assigns it",
            example = "[\"CREDIT_MANAGER\", \"FINANCE\"]")
    private Set<UserGroup> viewRoles;

    @NotNull(message = "Work roles are required")
    @Schema(description = "Who acts on the stage's items, besides SUPER_ADMIN", example = "[\"CREDIT_MANAGER\"]")
    private Set<UserGroup> workRoles;

    @NotNull(message = "Assign roles are required")
    @Schema(description = "Who gives the stage's items to others, besides SUPER_ADMIN", example = "[\"CREDIT_MANAGER\"]")
    private Set<UserGroup> assignRoles;

    @NotNull(message = "Target hours is required")
    @Min(value = 1, message = "Target hours must be at least 1")
    @Max(value = 720, message = "Target hours must be at most 720")
    @Schema(description = "Hours within which an item should leave the stage; later is overdue", example = "8")
    private Integer targetHours;

    @Min(value = 1, message = "Escalation hours must be at least 1")
    @Max(value = 1440, message = "Escalation hours must be at most 1440")
    @Schema(description = "Hours after which an item still waiting is escalated, at least the target; omit for no"
            + " escalation", example = "16")
    private Integer escalationHours;

    @NotNull(message = "Escalation roles are required")
    @Schema(description = "Who is emailed when an item is escalated", example = "[\"SUPER_ADMIN\", \"CREDIT_MANAGER\"]")
    private Set<UserGroup> escalateTo;

    @NotNull(message = "Notify assignee is required")
    @Schema(description = "Whether an escalation is also emailed to the item's assignee (for MORE_INFORMATION, the"
            + " application's originator)", example = "true")
    private Boolean notifyAssignee;

    @DecimalMin(value = "0.01", message = "Minimum principal must be more than zero")
    @Digits(integer = 17, fraction = 2, message = "Minimum principal must have at most 2 decimal places")
    @Schema(description = "Checkpoints only: hold only loans of at least this principal; omit for every amount",
            example = "2000.00")
    private BigDecimal minimumPrincipal;

    @Size(max = 20, message = "At most 20 channels")
    @Schema(description = "Checkpoints only: hold only applications from these channels (a channel id, or PORTAL for"
            + " applications with no channel); omit or leave empty for every channel", example = "[\"superapp\"]")
    private Set<@NotBlank(message = "A channel cannot be blank") String> channels;

    @Schema(description = "Checkpoints only: false stops it holding loans, true starts it again; omit to leave it as"
            + " it is", example = "true")
    private Boolean active;
}
