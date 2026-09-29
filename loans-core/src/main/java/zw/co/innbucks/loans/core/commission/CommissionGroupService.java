package zw.co.innbucks.loans.core.commission;

import zw.co.innbucks.loans.core.api.CommissionGroupResponse;
import zw.co.innbucks.loans.core.api.CreateCommissionGroupRequest;

import java.util.List;

public interface CommissionGroupService {

    /** The groups that can be assigned: enabled ones, by name. */
    List<CommissionGroupResponse> findEnabled();

    CommissionGroupResponse create(CreateCommissionGroupRequest request);
}
