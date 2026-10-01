package zw.co.innbucks.loans.core.borrower;

import java.time.Instant;
import java.util.List;

/**
 * What a valid middleware assertion proves, and nothing else from it is trusted: this phone was authenticated by the
 * middleware at {@code issuedAt}, by these methods (RFC 8176 {@code amr}: {@code pin}, {@code fpt}, {@code face}), under
 * this one-use id.
 */
public record VerifiedAssertion(String phone, Instant issuedAt, Instant expiresAt, String jti, List<String> methods) {
}
