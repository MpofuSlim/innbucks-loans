package zw.co.innbucks.loans.core.workflow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Who a work item goes to; the caller themselves when absent. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssignWorkItemRequest {

    @Size(max = 100, message = "Assignee must be at most 100 characters")
    @Schema(description = "Username to give the item to; omit to take it yourself", example = "rnyathi")
    private String assignee;
}
