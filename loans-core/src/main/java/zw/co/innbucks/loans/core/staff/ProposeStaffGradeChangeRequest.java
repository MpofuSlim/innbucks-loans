package zw.co.innbucks.loans.core.staff;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A proposal to retire or rename a grade in the matrix, for a second person to approve (FR-SGL-010). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposeStaffGradeChangeRequest {

    @NotNull(message = "Action is required (RETIRE or RENAME)")
    @Schema(description = "RETIRE takes the grade out of the matrix; RENAME gives it a new name", example = "RENAME")
    private StaffGradeChangeAction action;

    @NotBlank(message = "Grade is required")
    @Size(max = 64, message = StaffGrades.MESSAGE)
    @Pattern(regexp = StaffGrades.PATTERN, message = StaffGrades.MESSAGE)
    @Schema(description = "The grade in the matrix to retire or rename", example = "DRIVER/OFFICE ORDERLY")
    private String grade;

    @Size(max = 64, message = StaffGrades.MESSAGE)
    @Pattern(regexp = StaffGrades.PATTERN, message = StaffGrades.MESSAGE)
    @Schema(description = "For a RENAME, and only then: the grade's new name. Stored upper case, with runs of spaces as"
            + " one and none around a slash", example = "DRIVER/ORDERLY")
    private String newGrade;

    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(description = "Why, for the checker", example = "Human Capital renamed the band in the 2027 grading")
    private String comment;
}
