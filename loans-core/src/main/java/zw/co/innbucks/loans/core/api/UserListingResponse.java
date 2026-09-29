package zw.co.innbucks.loans.core.api;


import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UserListingResponse {
    private List<UserDto> users;
}
