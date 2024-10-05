package zw.co.reikan.loans.core.api;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.loans.core.user.UserGroup;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateUserRequest  {
	private String username;
	private String firstName;
	private String lastName;
	private String email;
	private String mobileNumber;
	private String idNumber;
	private String merchantCode;
	private String importKey;
	private List<UserGroup> groups;
}
