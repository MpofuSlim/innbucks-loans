package zw.co.innbucks.loans.core.exception;

import java.time.LocalDateTime;

/**
 * Sign-in refused because the account is locked after too many consecutive failed attempts. Rendered
 * as a 423 with {@code lockedUntil} and a {@code Retry-After}, the same contract as the ticketing
 * user-service, so the portal can say when to try again rather than "wrong password".
 */
public class AccountLockedException extends RuntimeException {

    /** UTC, like every stored time; rendered at the market offset on the wire. */
    private final LocalDateTime lockedUntil;

    public AccountLockedException(LocalDateTime lockedUntil) {
        super("Account temporarily locked due to too many failed sign-in attempts");
        this.lockedUntil = lockedUntil;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }
}
