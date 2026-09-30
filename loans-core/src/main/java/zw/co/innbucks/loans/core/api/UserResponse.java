package zw.co.innbucks.loans.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.util.List;

/**
 * A user account as the API shows it. Never the password or anything derived from it; {@code toString}
 * leaves out the contact details and ID number.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(
        Long id,
        String username,
        String firstName,
        String lastName,
        String email,
        String mobileNumber,
        String idNumber,
        Boolean temporaryPassword,
        List<UserGroup> groups,
        String merchantCode,
        String merchantName,
        CommissionGroupResponse commissionGroup,
        String physicalAddress,
        String creditAuthorityLevel) {

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .mobileNumber(user.getMobileNumber())
                .idNumber(user.getIdNumber())
                .temporaryPassword(user.getTemporaryPassword())
                .groups(user.getGroups() == null ? List.of() : user.getGroups().stream().sorted().toList())
                .merchantCode(user.getMerchant() == null ? null : user.getMerchant().getMerchantCode())
                .merchantName(user.getMerchant() == null ? null : user.getMerchant().getCompanyName())
                .commissionGroup(CommissionGroupResponse.from(user.getCommissionGroup()))
                .physicalAddress(user.getPhysicalAddress())
                .creditAuthorityLevel(user.getCreditAuthorityLevel())
                .build();
    }

    @Override
    public String toString() {
        return "UserResponse[id=" + id + ", username=" + username + ", groups=" + groups + "]";
    }
}
