package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A cash loan's payout moves only through {@link StaffLoan}'s own methods, each refusing a move its payout is not in a
 * state for: the bank books and pays on one call, so a payout sent twice, or cancelled while the bank may hold it, is
 * money paid out wrongly. The same rules are in V36's CHECKs; these pin them where the code decides.
 */
class StaffLoanPayoutTransitionsTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 29, 8, 0);
    private static final String REFERENCE = "SGL-2026-000143";

    private static StaffLoan cash(StaffLoanPayoutStatus payout) {
        return StaffLoan.builder().id(143L).reference(REFERENCE).status(StaffLoanStatus.AWAITING_DISBURSEMENT)
                .payoutMethod(StaffLoanPayoutMethod.CASH).payoutDestination(StaffLoanPayoutDestination.VEENGU_WALLET)
                .payoutAccount("263772123123").payoutStatus(payout)
                .payoutClaimedAt(payout == StaffLoanPayoutStatus.SENDING ? AT.minusMinutes(1) : null).build();
    }

    private static StaffLoan voucher() {
        return StaffLoan.builder().id(142L).reference("SGL-2026-000142").status(StaffLoanStatus.AWAITING_DISBURSEMENT)
                .build();
    }

    @Test
    @DisplayName("a loan built without a method is a voucher loan, with no payout")
    void theDefaultIsVoucher() {
        StaffLoan loan = voucher();

        assertThat(loan.getPayoutMethod()).isEqualTo(StaffLoanPayoutMethod.VOUCHER);
        assertThat(loan.getPayoutStatus()).isNull();
        assertThat(loan.payoutInFlight()).isFalse();
        assertThat(new StaffLoan().getPayoutMethod()).as("as Hibernate builds one").isEqualTo(
                StaffLoanPayoutMethod.VOUCHER);
    }

    @Test
    @DisplayName("a voucher loan has no payout to move, and cancels exactly as before")
    void aVoucherLoanHasNoPayout() {
        StaffLoan loan = voucher();

        assertThatThrownBy(() -> loan.claimPayout(AT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("voucher loan");
        assertThatThrownBy(() -> loan.notePayout("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> loan.paidOut(AT, null)).isInstanceOf(IllegalStateException.class);

        loan.cancel(AT, "credit.officer", "Accepted in error");

        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(loan.getPayoutStatus()).isNull();
    }

    @Test
    @DisplayName("READY -> SENDING: claimed, with when; an earlier hold message is cleared")
    void claim() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.READY);
        loan.holdPayout("Too close to the repayment date");

        loan.claimPayout(AT);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.SENDING);
        assertThat(loan.getPayoutClaimedAt()).isEqualTo(AT);
        assertThat(loan.getPayoutMessage()).isNull();
        assertThat(loan.payoutInFlight()).isTrue();
    }

    @ParameterizedTest(name = "a payout {0} is never claimed")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"READY"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyReadyIsClaimed(StaffLoanPayoutStatus status) {
        StaffLoan loan = cash(status);

        assertThatThrownBy(() -> loan.claimPayout(AT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REFERENCE).hasMessageContaining(String.valueOf(status));
        assertThat(loan.getPayoutStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("a cancelled loan's payout is never claimed, whatever it says")
    void aCancelledLoanIsNeverClaimed() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.READY);
        loan.cancel(AT, "credit.officer", "Accepted in error");

        assertThatThrownBy(() -> loan.claimPayout(AT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("SENDING -> READY only when it provably never left: the claim is released")
    void release() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);

        loan.releasePayoutClaim("InnBucks login failed - sent again on the next pay-out", null);

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        assertThat(loan.getPayoutClaimedAt()).isNull();
        assertThat(loan.getPayoutMessage()).startsWith("InnBucks login failed");
    }

    @Test
    @DisplayName("SENDING -> BOOKED on a 2xx: the bank has seen it, and that is never forgotten")
    void booked() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);

        loan.payoutBooked(AT, "{}");

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutClaimedAt()).isNull();
        assertThat(loan.getPayoutBankSeenAt()).isEqualTo(AT);
        assertThat(loan.getPayoutLastAnswer()).isEqualTo("{}");

        loan.payoutFound(AT.plusMinutes(3), "PROCESSING", "{\"status\":\"PROCESSING\"}");
        assertThat(loan.getPayoutBankSeenAt()).as("sticky: the first time").isEqualTo(AT);
        assertThat(loan.getPayoutBankStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("SENDING -> IN_DOUBT on an answer that proves nothing; SENDING -> REFUSED on a 4xx, which a person"
            + " may send again")
    void inDoubtAndRefused() {
        StaffLoan doubt = cash(StaffLoanPayoutStatus.SENDING);
        doubt.payoutInDoubt("Read timed out", null);
        assertThat(doubt.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(doubt.getPayoutClaimedAt()).isNull();
        assertThat(doubt.getPayoutBankSeenAt()).isNull();
        assertThat(doubt.payoutInFlight()).isTrue();

        StaffLoan refused = cash(StaffLoanPayoutStatus.SENDING);
        refused.payoutRefused("InnBucks answered HTTP 400", "{\"responseCode\":\"096\"}");
        assertThat(refused.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.REFUSED);
        assertThat(refused.getStatus()).as("no bank answer cancels a loan").isEqualTo(
                StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(refused.payoutInFlight()).isFalse();

        refused.sendRefusedPayoutAgain();
        assertThat(refused.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        assertThat(refused.getPayoutMessage()).isNull();
        assertThat(refused.getPayoutLastAnswer()).as("the refusal is kept until the next answer").contains("096");
    }

    @Test
    @DisplayName("the inquiry: found moves IN_DOUBT to BOOKED and clears a 'not found'; 'not found' is stamped once")
    void inquiry() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);
        loan.payoutInDoubt("HTTP 502", "<html>Bad gateway</html>");

        assertThat(loan.payoutNotFound(AT, "{\"responseCode\":\"004\"}")).isTrue();
        assertThat(loan.payoutNotFound(AT.plusHours(1), "{\"responseCode\":\"004\"}")).isFalse();
        assertThat(loan.getPayoutNotFoundAt()).isEqualTo(AT);
        assertThat(loan.getPayoutStatus()).as("never failed by itself").isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);

        loan.payoutFound(AT.plusHours(2), "PROCESSING", "{\"responseCode\":\"000\"}");

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutNotFoundAt()).isNull();
        assertThat(loan.getPayoutBankSeenAt()).isEqualTo(AT.plusHours(2));
    }

    @Test
    @DisplayName("BOOKED -> PAID: the loan is DISBURSED under its own reference, with the bank's loan account")
    void paidOut() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.BOOKED);

        loan.paidOut(AT, " LA-0001234 ");

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.PAID);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        assertThat(loan.getDisbursedAt()).isEqualTo(AT);
        assertThat(loan.getDisbursementReference()).isEqualTo(REFERENCE);
        assertThat(loan.getBankLoanAccount()).isEqualTo("LA-0001234");
        assertThat(loan.payoutInFlight()).isFalse();
        assertThatThrownBy(() -> loan.paidOut(AT, null)).as("never paid twice")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PAID without a loan account from the bank keeps the one already recorded")
    void paidOutKeepsTheLoanAccount() {
        StaffLoan loan = StaffLoan.builder().reference(REFERENCE).status(StaffLoanStatus.AWAITING_DISBURSEMENT)
                .payoutMethod(StaffLoanPayoutMethod.CASH).payoutStatus(StaffLoanPayoutStatus.IN_DOUBT)
                .bankLoanAccount("LA-1").build();

        loan.paidOut(AT, null);

        assertThat(loan.getBankLoanAccount()).isEqualTo("LA-1");
    }

    @ParameterizedTest(name = "a payout {0} is never paid out")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"BOOKED", "IN_DOUBT"}, mode = EnumSource.Mode.EXCLUDE)
    void onlyABookingTheBankHoldsIsPaid(StaffLoanPayoutStatus status) {
        StaffLoan loan = cash(status);

        assertThatThrownBy(() -> loan.paidOut(AT, null)).isInstanceOf(IllegalStateException.class);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
    }

    @ParameterizedTest(name = "a payout {0} is cancelled: WITHDRAWN")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"READY", "REFUSED"})
    void nothingBookedIsWithdrawn(StaffLoanPayoutStatus status) {
        StaffLoan loan = cash(status);

        loan.cancel(AT, "credit.officer", "Borrower asked to cancel");

        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.WITHDRAWN);
        assertThat(loan.getCancelledBy()).isEqualTo("credit.officer");
    }

    @ParameterizedTest(name = "a payout {0} may be in the bank's hands: never cancelled")
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"SENDING", "BOOKED", "IN_DOUBT"})
    void inFlightIsNeverCancelled(StaffLoanPayoutStatus status) {
        StaffLoan loan = cash(status);

        assertThatThrownBy(() -> loan.cancel(AT, "credit.officer", "Borrower asked to cancel"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(loan.getPayoutStatus()).isEqualTo(status);
        assertThat(loan.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("not booked, missing: only IN_DOUBT, never seen by the bank, reported not found long enough ago, and"
            + " still reported so lately")
    void mayConfirmNotBooked() {
        LocalDateTime cutoff = AT.minusHours(24);
        LocalDateTime lately = AT.minusMinutes(6);
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);
        loan.payoutInDoubt("Read timed out", null);
        assertThat(loan.mayConfirmNotBooked(cutoff, lately)).as("never reported not found").isFalse();

        loan.payoutNotFound(AT.minusHours(23), null);
        assertThat(loan.mayConfirmNotBooked(cutoff, AT.minusHours(24))).as("not found too recently").isFalse();
        assertThatThrownBy(() -> loan.payoutNotBooked(AT, cutoff, AT.minusHours(24), "super.admin", "Bank confirmed"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(loan.mayConfirmNotBooked(AT.minusHours(23), AT.minusHours(24))).isTrue();

        StaffLoan seen = cash(StaffLoanPayoutStatus.SENDING);
        seen.payoutBooked(AT.minusDays(3), null);
        seen.payoutNotFound(AT.minusMinutes(1), null);
        assertThat(seen.mayConfirmNotBooked(AT, lately)).as("BOOKED: the bank once said it holds it").isFalse();
    }

    @Test
    @DisplayName("not booked needs the bank still saying so: a first 004 long ago, then nothing answered since, is not"
            + " enough")
    void notBookedNeedsARecentAnswer() {
        LocalDateTime cutoff = AT.minusHours(24);
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);
        loan.payoutInDoubt("Read timed out", null);
        loan.payoutNotFound(AT.minusHours(30), null);

        assertThat(loan.getPayoutCheckedAt()).isEqualTo(AT.minusHours(30));
        assertThat(loan.mayConfirmNotBooked(cutoff, AT.minusMinutes(6)))
                .as("the 004 is old, and the inquiry has not answered since").isFalse();

        loan.payoutNotFound(AT.minusMinutes(2), null);
        assertThat(loan.getPayoutNotFoundAt()).as("the first 004 is kept").isEqualTo(AT.minusHours(30));
        assertThat(loan.getPayoutCheckedAt()).isEqualTo(AT.minusMinutes(2));
        assertThat(loan.mayConfirmNotBooked(cutoff, AT.minusMinutes(6))).isTrue();

        loan.payoutFound(AT.minusMinutes(1), "PROCESSING", null);
        assertThat(loan.mayConfirmNotBooked(cutoff, AT.minusMinutes(6))).as("found since").isFalse();
    }

    @Test
    @DisplayName("not booked, declined: BOOKED or IN_DOUBT, held by the bank in a declined status long enough ago and"
            + " still lately, even though the bank has seen it")
    void declinedMayBeConfirmedNotBooked() {
        LocalDateTime cutoff = AT.minusHours(24);
        LocalDateTime lately = AT.minusMinutes(6);
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);
        loan.payoutBooked(AT.minusDays(3), "{}");

        assertThat(loan.payoutDeclined(AT.minusHours(30), "REJECTED", "{}")).isTrue();
        assertThat(loan.payoutDeclined(AT.minusMinutes(3), "REJECTED", "{}")).as("dated once").isFalse();

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutDeclinedAt()).isEqualTo(AT.minusHours(30));
        assertThat(loan.getPayoutBankStatus()).isEqualTo("REJECTED");
        assertThat(loan.getPayoutBankSeenAt()).isEqualTo(AT.minusDays(3));
        assertThat(loan.mayConfirmNotBooked(cutoff, lately)).isTrue();
        assertThat(loan.mayConfirmNotBooked(AT.minusHours(31), lately)).as("declined too recently").isFalse();
        assertThat(loan.mayConfirmNotBooked(cutoff, AT)).as("not asked lately").isFalse();

        loan.payoutNotBooked(AT, cutoff, lately, "super.admin", "The bank rejected it");
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.FAILED);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);

        StaffLoan again = cash(StaffLoanPayoutStatus.SENDING);
        again.payoutBooked(AT.minusDays(3), "{}");
        again.payoutDeclined(AT.minusHours(30), "REJECTED", "{}");
        again.payoutFound(AT.minusMinutes(1), "PROCESSING", "{}");
        assertThat(again.getPayoutDeclinedAt()).as("any other status clears it").isNull();
        assertThat(again.mayConfirmNotBooked(cutoff, lately)).isFalse();
    }

    @Test
    @DisplayName("a 2xx that lands after the payout was held IN_DOUBT books it, seen by the bank; on a BOOKED one it only"
            + " keeps the answer")
    void bookedLate() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);
        loan.payoutInDoubt("abandoned", null);

        loan.payoutBookedLate(AT, "{\"responseCode\":\"000\"}");

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(loan.getPayoutBankSeenAt()).isEqualTo(AT);
        assertThat(loan.getPayoutApplyAnswer()).contains("000");
        assertThat(loan.getPayoutMessage()).isNull();
        assertThatThrownBy(() -> cash(StaffLoanPayoutStatus.READY).payoutBookedLate(AT, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("not booked: FAILED, and the loan cancelled by the person who confirmed it")
    void notBooked() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);
        loan.payoutInDoubt("Read timed out", null);
        loan.payoutNotFound(AT.minusDays(2), null);

        loan.payoutNotBooked(AT, AT.minusHours(24), AT.minusDays(3), "super.admin",
                "The bank confirmed it was never booked");

        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.FAILED);
        assertThat(loan.getPayoutFailedAt()).isEqualTo(AT);
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(loan.getCancelledAt()).isEqualTo(AT);
        assertThat(loan.getCancelledBy()).isEqualTo("super.admin");
        assertThat(loan.getCancellationReason()).isEqualTo("The bank confirmed it was never booked");
    }

    @Test
    @DisplayName("found after it was confirmed not booked: recorded once; the loan stays cancelled")
    void paidAfterCancel() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);
        loan.payoutInDoubt("Read timed out", null);
        loan.payoutNotFound(AT.minusDays(2), null);
        loan.payoutNotBooked(AT, AT.minusHours(24), AT.minusDays(3), "super.admin", "Bank confirmed");

        assertThat(loan.payoutPaidAfterCancel(AT.plusDays(1), "SETTLED", "{}")).isTrue();
        assertThat(loan.payoutPaidAfterCancel(AT.plusDays(2), "SETTLED", "{}")).isFalse();

        assertThat(loan.getPayoutPaidAfterCancelAt()).isEqualTo(AT.plusDays(1));
        assertThat(loan.getPayoutBankSeenAt()).isEqualTo(AT.plusDays(1));
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(loan.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.FAILED);
        assertThatThrownBy(() -> cash(StaffLoanPayoutStatus.BOOKED).payoutPaidAfterCancel(AT, "SETTLED", "{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a hold note only on a READY payout; any cash payout may carry a note")
    void notes() {
        StaffLoan ready = cash(StaffLoanPayoutStatus.READY);
        ready.holdPayout("Too close to the repayment date");
        assertThat(ready.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.READY);
        assertThat(ready.getPayoutMessage()).isEqualTo("Too close to the repayment date");

        assertThatThrownBy(() -> cash(StaffLoanPayoutStatus.SENDING).holdPayout("x"))
                .isInstanceOf(IllegalStateException.class);
        StaffLoan booked = cash(StaffLoanPayoutStatus.BOOKED);
        booked.notePayout("Status check returned no answer");
        assertThat(booked.getPayoutMessage()).isEqualTo("Status check returned no answer");
    }

    @Test
    @DisplayName("a message and an answer longer than their columns are cut to them")
    void longTextIsCut() {
        StaffLoan loan = cash(StaffLoanPayoutStatus.SENDING);

        loan.payoutInDoubt("m".repeat(600), "a".repeat(3_000));

        assertThat(loan.getPayoutMessage()).hasSize(StaffLoan.MESSAGE_LENGTH);
        assertThat(loan.getPayoutLastAnswer()).hasSize(StaffLoan.ANSWER_LENGTH);
    }

    @Test
    @DisplayName("the statuses: in flight, settled by the inquiry, cancellable, pending")
    void statuses() {
        assertThat(EnumSet.allOf(StaffLoanPayoutStatus.class).stream().filter(StaffLoanPayoutStatus::inFlight))
                .containsExactlyInAnyOrder(StaffLoanPayoutStatus.SENDING, StaffLoanPayoutStatus.BOOKED,
                        StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(EnumSet.allOf(StaffLoanPayoutStatus.class).stream().filter(StaffLoanPayoutStatus::awaitsInquiry))
                .containsExactlyInAnyOrder(StaffLoanPayoutStatus.BOOKED, StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(EnumSet.allOf(StaffLoanPayoutStatus.class).stream().filter(StaffLoanPayoutStatus::cancellable))
                .containsExactlyInAnyOrder(StaffLoanPayoutStatus.READY, StaffLoanPayoutStatus.REFUSED);
        assertThat(StaffLoanPayoutStatus.PENDING).containsExactlyInAnyOrder(StaffLoanPayoutStatus.READY,
                StaffLoanPayoutStatus.SENDING, StaffLoanPayoutStatus.BOOKED, StaffLoanPayoutStatus.IN_DOUBT,
                StaffLoanPayoutStatus.REFUSED);
        assertThat(StaffLoanPayoutDestination.VEENGU_WALLET.label()).isEqualTo("InnBucks wallet");
        assertThat(StaffLoanPayoutDestination.BR_ACCOUNT.label()).isEqualTo("InnBucks account");
    }

    @Test
    @DisplayName("the backlog's oldest age: since the claim while SENDING, else since acceptance; zero for none")
    void backlogAge() {
        LocalDateTime now = AT.plusHours(1);
        assertThat(new StaffLoanPayoutBacklog(StaffLoanPayoutStatus.SENDING, 1L, AT.minusDays(1), AT).oldestAge(now))
                .hasHours(1);
        assertThat(new StaffLoanPayoutBacklog(StaffLoanPayoutStatus.READY, 2L, AT, null).oldestAge(now)).hasHours(1);
        assertThat(new StaffLoanPayoutBacklog(StaffLoanPayoutStatus.READY, 0L, null, null).oldestAge(now)).isZero();
    }
}
