package zw.co.innbucks.loans.core.borrower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.NOW;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.ZW;

/** An assertion is spent once: a second use, at once or later, is refused like a forged one. */
class AssertionUsesTest {

    private final BorrowerAssertionUseRepository repository = mock(BorrowerAssertionUseRepository.class);
    private final AssertionUses uses = new AssertionUses(repository, ZW);
    private final VerifiedAssertion assertion = new VerifiedAssertion("+263773456789", NOW.minusSeconds(60),
            NOW.plusSeconds(240), "mw-7c1d9e2a", List.of("pin"));

    @Test
    @DisplayName("the first use is recorded with what it was for, for whom, and until when it could have been used")
    void firstUseRecorded() {
        uses.spend(assertion, BorrowerAssertionUse.Purpose.SIGN_IN, 2L);

        ArgumentCaptor<BorrowerAssertionUse> saved = ArgumentCaptor.forClass(BorrowerAssertionUse.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getJti()).isEqualTo("mw-7c1d9e2a");
        assertThat(saved.getValue().getPurpose()).isEqualTo(BorrowerAssertionUse.Purpose.SIGN_IN);
        assertThat(saved.getValue().getStaffMemberId()).isEqualTo(2L);
        assertThat(saved.getValue().getUsedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 8, 0, 0));
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 8, 4, 0));
    }

    @Test
    @DisplayName("rows a day past their assertion's expiry are swept on the way in")
    void sweepsOldRows() {
        uses.spend(assertion, BorrowerAssertionUse.Purpose.STEP_UP, 2L);

        verify(repository).deleteExpiredBefore(LocalDateTime.of(2026, 9, 30, 8, 0, 0));
    }

    @Test
    @DisplayName("an assertion already used is refused, and nothing is recorded")
    void replayRefused() {
        when(repository.existsById("mw-7c1d9e2a")).thenReturn(true);

        assertThatThrownBy(() -> uses.spend(assertion, BorrowerAssertionUse.Purpose.SIGN_IN, 2L))
                .isInstanceOf(AssertionRejectedException.class)
                .hasMessageContaining("replayed");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("two uses at the same moment: the one that loses the insert is refused, not a 500")
    void concurrentReplayRefused() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> uses.spend(assertion, BorrowerAssertionUse.Purpose.SIGN_IN, 2L))
                .isInstanceOf(AssertionRejectedException.class)
                .hasMessageContaining("concurrent");
    }
}
