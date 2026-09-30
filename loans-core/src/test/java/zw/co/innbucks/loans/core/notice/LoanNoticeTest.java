package zw.co.innbucks.loans.core.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanStage;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanNoticeTest {

    private static Loan loan() {
        Loan loan = Loan.builder().disbursedAmount(new BigDecimal("319.15")).build();
        loan.setId(43L);
        return loan;
    }

    @Test
    @DisplayName("every stage an application can reach has a notice, so the applicant hears of each (FR-SSB-016)")
    void everyStageIsAnnounced() {
        Set<LoanStage> announced = EnumSet.noneOf(LoanStage.class);
        Arrays.stream(LoanNotice.values()).map(LoanNotice::stage).forEach(announced::add);

        assertThat(announced).containsExactlyInAnyOrder(LoanStage.values());
    }

    @Test
    @DisplayName("every notice worded here names the loan's reference")
    void everyNoticeNamesTheReference() {
        for (LoanNotice notice : LoanNotice.values()) {
            if (notice != LoanNotice.PAID) {
                assertThat(notice.textFor(loan())).as(notice.name()).contains("000000043");
            }
        }
    }

    @Test
    @DisplayName("each notice says something different, so the applicant can tell the stages apart")
    void noticesAreWordedDifferently() {
        long distinct = Arrays.stream(LoanNotice.values())
                .filter(notice -> notice != LoanNotice.PAID)
                .map(notice -> notice.textFor(loan()))
                .distinct()
                .count();

        assertThat(distinct).isEqualTo(LoanNotice.values().length - 1L);
    }

    @Test
    @DisplayName("SSB's confirmation does not read as a loan approval: Credit has not decided yet")
    void ssbConfirmationIsNotAnApproval() {
        assertThat(LoanNotice.SSB_CONFIRMED.textFor(loan())).doesNotContain("approved");
        assertThat(LoanNotice.APPROVED.textFor(loan())).contains("approved", "319.15");
    }

    @Test
    @DisplayName("the paid notice is worded by the payout, which knows where the money went")
    void paidIsWordedByItsSender() {
        assertThatThrownBy(() -> LoanNotice.PAID.textFor(loan()))
                .isInstanceOf(IllegalStateException.class);
    }
}
