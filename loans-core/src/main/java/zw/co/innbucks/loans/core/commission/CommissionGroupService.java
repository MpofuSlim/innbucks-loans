package zw.co.innbucks.loans.core.commission;

import zw.co.innbucks.loans.core.api.CommissionGroupDto;
import zw.co.innbucks.loans.core.api.CreateCommissionGroupRequest;

public interface CommissionGroupService {

    CommissionGroupDto create(CreateCommissionGroupRequest request);
}
