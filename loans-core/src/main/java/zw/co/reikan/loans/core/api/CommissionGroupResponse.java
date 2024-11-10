package zw.co.reikan.loans.core.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@RequiredArgsConstructor
public class CommissionGroupResponse {
    private final List<CommissionGroupDto> commissionGroups;
}
