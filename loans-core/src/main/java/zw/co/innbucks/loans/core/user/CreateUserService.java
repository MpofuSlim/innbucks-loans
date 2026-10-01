package zw.co.innbucks.loans.core.user;

import zw.co.innbucks.loans.core.api.CreateUserRequest;
import zw.co.innbucks.loans.core.api.ForgotPasswordRequest;
import zw.co.innbucks.loans.core.api.UserResponse;


public interface CreateUserService {

    /** Sends a fresh temporary password to the account's mobile number; an unknown username does nothing. */
    void resetPassword(ForgotPasswordRequest request);

    /** Creates the user in the merchant and sends them a temporary password, by WhatsApp or else SMS. */
    UserResponse create(CreateUserRequest request, String merchantCode);
}
