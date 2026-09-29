package zw.co.innbucks.loans.core.merchant;

import org.mapstruct.Mapper;
import zw.co.innbucks.loans.core.api.CommissionGroupResponse;
import zw.co.innbucks.loans.core.api.MerchantResponse;
import zw.co.innbucks.loans.core.commission.CommissionGroup;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MerchantMapper {

    MerchantResponse toResponse(Merchant merchant);

    List<MerchantResponse> toResponses(List<Merchant> merchants);

    default CommissionGroupResponse toResponse(CommissionGroup group) {
        return CommissionGroupResponse.from(group);
    }
}
