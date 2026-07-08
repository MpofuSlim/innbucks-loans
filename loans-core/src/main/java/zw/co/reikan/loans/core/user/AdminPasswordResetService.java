package zw.co.reikan.loans.core.user;

import zw.co.reikan.loans.core.api.AdminResetPasswordRequest;
import zw.co.reikan.loans.core.api.UserDTO;

public interface AdminPasswordResetService {

    /**
     * Reset {@code request.username}'s password to a fresh temporary one and
     * deliver it over the requested channel. Delivery is attempted BEFORE the new
     * password is persisted — if delivery fails the reset is aborted and the old
     * password remains valid, so a user is never left locked out with an unsent
     * password. Returns the (password-free) user details; never the password.
     */
    UserDTO resetPassword(AdminResetPasswordRequest request);
}
