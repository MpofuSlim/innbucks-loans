package zw.co.innbucks.loans.core.ndasenda;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Builder
@Data
public class FindNdasendaBatchResponse {
    private List<NdasendaDeductionBatch> batches;
}
