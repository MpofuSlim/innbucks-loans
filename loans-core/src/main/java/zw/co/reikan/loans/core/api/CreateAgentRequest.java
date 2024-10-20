package zw.co.reikan.loans.core.api;


import lombok.Data;
import zw.co.reikan.loans.core.user.UserGroup;

@Data
public class CreateAgentRequest {
    private String username;
    private String firstName;
    private String lastName;
    private String email;
    private String mobileNumber;
    private String idNumber;
    private UserGroup group;
}
