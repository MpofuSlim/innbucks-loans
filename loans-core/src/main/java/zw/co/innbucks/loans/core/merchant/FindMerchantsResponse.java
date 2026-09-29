package zw.co.innbucks.loans.core.merchant;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import zw.co.innbucks.loans.core.api.MerchantDto;

import java.util.List;

@Data
@RequiredArgsConstructor
public class FindMerchantsResponse {
    private final List<MerchantDto> merchants;
}
