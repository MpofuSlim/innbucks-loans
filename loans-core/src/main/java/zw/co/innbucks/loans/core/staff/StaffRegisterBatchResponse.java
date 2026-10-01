package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A submission to the staff register and where it stands.
 *
 * @param ignoredColumns on an upload's response only: header cells the file had that the register does not use
 * @param rejected       on an upload's response only: every row refused, with its reasons (FR-SGL-003)
 * @param createdRows    on an approved batch: how its staged rows were applied, with {@code amendedRows},
 *                       {@code unchangedRows} and {@code skippedRows}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffRegisterBatchResponse(
        Long id,
        StaffRegisterBatchSource source,
        String fileName,
        StaffRegisterBatchStatus status,
        String submittedBy,
        LocalDateTime submittedAt,
        String comment,
        int totalRows,
        int stagedRows,
        int rejectedRows,
        String decidedBy,
        LocalDateTime decidedAt,
        String decisionComment,
        Integer createdRows,
        Integer amendedRows,
        Integer unchangedRows,
        Integer skippedRows,
        List<String> ignoredColumns,
        List<StaffRegisterRowResponse> rejected) {

    static StaffRegisterBatchResponse of(StaffRegisterBatch batch) {
        return of(batch, null, null);
    }

    static StaffRegisterBatchResponse of(StaffRegisterBatch batch, List<String> ignoredColumns,
                                         List<StaffRegisterRowResponse> rejected) {
        return new StaffRegisterBatchResponse(batch.getId(), batch.getSource(), batch.getFileName(), batch.getStatus(),
                batch.getSubmittedBy(), batch.getSubmittedAt(), batch.getSubmissionComment(), batch.getTotalRows(),
                batch.getStagedRows(), batch.getRejectedRows(), batch.getDecidedBy(), batch.getDecidedAt(),
                batch.getDecisionComment(), batch.getCreatedRows(), batch.getAmendedRows(), batch.getUnchangedRows(),
                batch.getSkippedRows(), ignoredColumns, rejected);
    }
}
