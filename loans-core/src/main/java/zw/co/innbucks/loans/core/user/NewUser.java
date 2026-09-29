package zw.co.innbucks.loans.core.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

/** An account to create, as {@link CreateUserServiceImpl} assembles it from a request and its merchant. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewUser {
    private String username;
    private String firstName;
    private String lastName;
    private String email;
    private String mobileNumber;
    @ToString.Exclude
    private String idNumber;
    private String merchantCode;
    private List<UserGroup> groups;
    private Long commissionGroupId;
    private String physicalAddress;
}
