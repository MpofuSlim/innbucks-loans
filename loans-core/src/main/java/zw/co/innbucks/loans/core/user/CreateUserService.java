package zw.co.innbucks.loans.core.user;

import zw.co.innbucks.loans.core.api.CreateAgentRequest;
import zw.co.innbucks.loans.core.api.CreateUserRequest;
import zw.co.innbucks.loans.core.api.CreateUserResponse;
import zw.co.innbucks.loans.core.api.ForgotPasswordRequest;

public interface CreateUserService {
    void resetPassword(ForgotPasswordRequest request);

    CreateUserResponse create(CreateUserRequest createUserRequest);

    CreateUserResponse create(CreateAgentRequest createAgentRequest, String merchantCode);
}
