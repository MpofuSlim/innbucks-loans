package zw.co.innbucks.loans.core.borrower;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.config.MarketTimeZone;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * One use per assertion: its {@code jti} is recorded in the caller's transaction, so the use stands or falls with what
 * it was used for (a sign-in, an approved loan), and a second use of the same assertion, at once or later, is refused.
 * Loans has no Redis, so the record is a row; rows a day past their assertion's expiry are swept on the way in, since
 * an expired assertion is refused on its expiry anyway.
 */
@Component
@RequiredArgsConstructor
public class AssertionUses {

    private final BorrowerAssertionUseRepository repository;
    private final MarketTimeZone marketTimeZone;

    /**
     * Spends the assertion.
     *
     * @throws AssertionRejectedException it was used before
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void spend(VerifiedAssertion assertion, BorrowerAssertionUse.Purpose purpose, Long staffMemberId) {
        LocalDateTime now = marketTimeZone.nowUtc();
        repository.deleteExpiredBefore(now.minusDays(1));
        if (repository.existsById(assertion.jti())) {
            throw new AssertionRejectedException("replayed jti");
        }
        try {
            repository.saveAndFlush(BorrowerAssertionUse.builder()
                    .jti(assertion.jti())
                    .purpose(purpose)
                    .staffMemberId(staffMemberId)
                    .usedAt(now)
                    .expiresAt(LocalDateTime.ofInstant(assertion.expiresAt(), ZoneOffset.UTC))
                    .build());
        } catch (DataIntegrityViolationException race) {
            throw new AssertionRejectedException("replayed jti (concurrent use)");
        }
    }
}
