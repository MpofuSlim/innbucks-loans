package zw.co.reikan.loans.core.user;


import zw.co.reikan.loans.core.api.UserDTO;

public class SaveUserResponse {

    private UserDTO user;

    public UserDTO getUser() {
        return user;
    }

    public void setUser(UserDTO user) {
        this.user = user;
    }

}
