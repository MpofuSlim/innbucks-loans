package zw.co.innbucks.loans.core.user;

import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.notifications.NotificationChannel;


public interface AdminPasswordResetService {

    /**
     * Reset the user's password to a fresh temporary one and
     * deliver it over the requested channel. Delivery is attempted BEFORE the new
     * password is persisted — if delivery fails the reset is aborted and the old
     * password remains valid, so a user is never left locked out with an unsent
     * password. Returns the (password-free) user details; never the password.
     *
     * @throws zw.co.innbucks.loans.core.exception.NotFoundException no user has this id
     */
    UserResponse resetPassword(Long userId, NotificationChannel channel);
}
