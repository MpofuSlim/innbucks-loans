package zw.co.reikan.loans.core.user;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class UserRequest implements Serializable {
    private String username;
    private String password;
    private Role role;
    private String mobileNumber;
}