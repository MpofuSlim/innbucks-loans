package zw.co.innbucks.loans.core.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;
import zw.co.innbucks.loans.core.user.UserGroup;

/** A user to create in a merchant: an agent, a credit manager or finance. The temporary password is sent by SMS. */
@Data
public class CreateUserRequest {
    @NotBlank(message = "Username is required")
    @Schema(example = "tmoyo")
    private String username;
    @NotBlank(message = "First name is required")
    @Schema(example = "Tendai")
    private String firstName;
    @NotBlank(message = "Last name is required")
    @Schema(example = "Moyo")
    private String lastName;
    @Email(message = "Email is not valid")
    @Schema(example = "tendai.moyo@example.co.zw")
    private String email;
    @NotBlank(message = "Mobile number is required")
    @Schema(example = "+263771234567")
    private String mobileNumber;
    @ToString.Exclude
    @NotBlank(message = "ID number is required")
    @Schema(example = "63-1234567-A-42")
    private String idNumber;
    @NotNull(message = "User group is required")
    @Schema(example = "AGENTS")
    private UserGroup group;
    /** Required unless the merchant's commission structure is MERCHANT_DEFINED; 0 means the default group. */
    @Schema(example = "3")
    private Long commissionGroupId;
    @Schema(example = "12 Samora Machel Ave, Harare")
    private String physicalAddress;
}
