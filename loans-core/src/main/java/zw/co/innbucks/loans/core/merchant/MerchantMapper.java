package zw.co.innbucks.loans.core.merchant;

import org.mapstruct.Mapper;
import zw.co.innbucks.loans.core.api.MerchantDto;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MerchantMapper {

    MerchantDto fromMerchant(Merchant merchant);

    List<MerchantDto> fromMerchants(List<Merchant> merchants);
}
