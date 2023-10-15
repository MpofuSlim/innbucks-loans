package zw.co.reikan.loans.core.api;

import lombok.Data;

@Data
public class CreateUserRequest {
    private String userName;
    private String emailAddress;
    private String firstName;
    private String lastName;
    private String password;
}
