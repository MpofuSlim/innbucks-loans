package zw.co.reikan.loans.core.user;

import zw.co.reikan.loans.core.api.CreateAgentRequest;
import zw.co.reikan.loans.core.api.CreateUserRequest;
import zw.co.reikan.loans.core.api.CreateUserResponse;

public interface CreateUserService {
    CreateUserResponse create(CreateUserRequest createUserRequest);

    CreateUserResponse create(CreateAgentRequest createAgentRequest, User parentAgent, String merchantCode);
}
