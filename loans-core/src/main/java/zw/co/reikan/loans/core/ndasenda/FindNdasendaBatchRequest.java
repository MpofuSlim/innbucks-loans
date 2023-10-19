package zw.co.reikan.loans.core.ndasenda;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
public class FindNdasendaBatchRequest {
    @Schema(description = "format yyyy-MM-dd")
    private LocalDate startDate;
    @Schema(description = "format yyyy-MM-dd")
    private LocalDate endDate;
    private DeductionBatchStatus batchStatus;
}
