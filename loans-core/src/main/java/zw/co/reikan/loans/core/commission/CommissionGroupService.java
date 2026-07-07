package zw.co.reikan.loans.core.commission;

import zw.co.reikan.loans.core.api.CommissionGroupDto;
import zw.co.reikan.loans.core.api.CreateCommissionGroupRequest;

public interface CommissionGroupService {

    CommissionGroupDto create(CreateCommissionGroupRequest request);
}
