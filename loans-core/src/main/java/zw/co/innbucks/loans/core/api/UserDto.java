package zw.co.innbucks.loans.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.util.List;

@Data
@JsonInclude(Include.NON_NULL)
@Builder
@RequiredArgsConstructor
@AllArgsConstructor
public class UserDto {
	private Long id;
	private String username;
	private String firstName;
	private String lastName;
	@ToString.Exclude
	private String password;
	private String email;
	private String mobileNumber;
	@ToString.Exclude
	private String idNumber;
	private Boolean temporaryPassword;
	private String importKey;
	private String externalSystemId;
	private List<UserGroup> groups;
	private MerchantDto merchant;
	private CommissionGroupDto commissionGroup;
	private String physicalAddress;


	public static UserDto fromUser(User user) {
		UserDto userDto = new UserDto();
		userDto.setUsername(user.getUsername());
		userDto.setId(user.getId());
		userDto.setExternalSystemId(user.getExternalSystemId());
		userDto.setCommissionGroup(CommissionGroupDto.fromCommissionGroup(user.getCommissionGroup()));
		userDto.setPhysicalAddress(userDto.getPhysicalAddress());
		return userDto;
	}

}
