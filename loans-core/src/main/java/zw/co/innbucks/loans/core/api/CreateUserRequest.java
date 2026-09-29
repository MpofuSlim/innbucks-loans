package zw.co.innbucks.loans.core.api;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateUserRequest {
    private String username;
    private String firstName;
    private String lastName;
    private String email;
    private String mobileNumber;
    @ToString.Exclude
    private String idNumber;
    private String merchantCode;
    private String importKey;
    private List<UserGroup> groups;
    private CommissionStructure commissionStructure;
    private Long commissionGroupId;
    private String physicalAddress;

}
