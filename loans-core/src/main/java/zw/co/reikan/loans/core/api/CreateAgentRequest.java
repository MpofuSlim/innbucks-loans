package zw.co.reikan.loans.core.api;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;


import jakarta.persistence.Column;
import lombok.Data;
import zw.co.reikan.loans.core.user.UserGroup;

@Data
public class CreateAgentRequest {
    @NotBlank(message = "Username is required")
    private String username;
    @NotBlank(message = "First name is required")
    private String firstName;
    private String lastName;
    private String email;
    @NotBlank(message = "Mobile number is required")
    private String mobileNumber;
    @NotBlank(message = "ID number is required")
    private String idNumber;
    @NotNull(message = "User group is required")
    private UserGroup group;
    private Long commissionGroupId;
    private String physicalAddress;
}
