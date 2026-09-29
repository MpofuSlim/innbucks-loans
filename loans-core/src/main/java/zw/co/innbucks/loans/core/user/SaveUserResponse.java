package zw.co.innbucks.loans.core.user;


import zw.co.innbucks.loans.core.api.UserDto;

public class SaveUserResponse {

    private UserDto user;

    public UserDto getUser() {
        return user;
    }

    public void setUser(UserDto user) {
        this.user = user;
    }

}
