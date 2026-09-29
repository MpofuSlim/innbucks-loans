package zw.co.innbucks.loans.core.api;

import lombok.*;

import java.util.List;

@Data
@Builder
@RequiredArgsConstructor
public class CommissionGroupResponse {
    private final List<CommissionGroupDto> commissionGroups;
}
