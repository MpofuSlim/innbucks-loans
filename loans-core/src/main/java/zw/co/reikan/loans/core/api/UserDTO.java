package zw.co.reikan.loans.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;

import java.util.List;

@Data
@JsonInclude(Include.NON_NULL)
@Builder
@RequiredArgsConstructor
@AllArgsConstructor
public class UserDTO {
	private Long id;
	private String username;
	private String firstName;
	private String lastName;
	private String password;
	private String email;
	private String mobileNumber;
	private String idNumber;
	private Boolean temporaryPassword;
	private String importKey;
	private String externalSystemId;
	private List<UserGroup> groups;
	private MerchantDto merchant;

	public static UserDTO fromUser(User user) {
		UserDTO userDTO = new UserDTO();
		userDTO.setUsername(user.getUsername());
		userDTO.setId(user.getId());
		userDTO.setExternalSystemId(user.getExternalSystemId());
		return userDTO;
	}

}
