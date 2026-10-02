package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.offer.StaffLoanStanding.Standing;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A member's standing from loans' own records (FR-SGL-013, FR-SGL-014, FR-SGL-017): a loan awaiting disbursement or
 * disbursed is an active loan, and also arrears once it is disbursed and unpaid past its due date and the grace; a
 * written-off one is a written-off balance, which only Credit's override lifts. The clock stands at 2026-11-23, three
 * days after a 20 November due date.
 */
class RecordedStaffLoanStandingTest {

    private final List<StaffLoan> loans = new ArrayList<>();
    private final StaffLoanRepository repository = mock(StaffLoanRepository.class);
    private final StaffLoanProperties properties = new StaffLoanProperties();
    private final RecordedStaffLoanStanding standing = new RecordedStaffLoanStanding(repository,
            new StaffLoanPolicy(properties, new VoucherProperties()),
            new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC)));

    {
        when(repository.findByStaffMemberIdInAndStatusIn(any(), any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            Collection<StaffLoanStatus> statuses = i.getArgument(1);
            return loans.stream().filter(l -> ids.contains(l.getStaffMemberId()) && statuses.contains(l.getStatus()))
                    .toList();
        });
    }

    private void loan(long memberId, StaffLoanStatus status, LocalDate due) {
        loans.add(StaffLoan.builder().id((long) loans.size() + 1).staffMemberId(memberId).status(status).dueDate(due)
                .amount(new BigDecimal("300.00")).totalRepayable(new BigDecimal("300.00")).build());
    }

    private static StaffMember member(long id) {
        return StaffMember.builder().id(id).build();
    }

    @Test
    @DisplayName("awaiting disbursement or disbursed and not yet due: an active loan; repaid or cancelled: clear")
    void activeLoans() {
        loan(1, StaffLoanStatus.AWAITING_DISBURSEMENT, LocalDate.of(2026, 11, 20));
        loan(2, StaffLoanStatus.DISBURSED, LocalDate.of(2026, 12, 20));
        loan(3, StaffLoanStatus.REPAID, LocalDate.of(2026, 11, 20));
        loan(4, StaffLoanStatus.CANCELLED, LocalDate.of(2026, 11, 20));

        assertThat(standing.of(List.of(member(1), member(2), member(3), member(4), member(5))))
                .isEqualTo(Map.of(1L, Set.of(Standing.ACTIVE_LOAN), 2L, Set.of(Standing.ACTIVE_LOAN)));
    }

    @Test
    @DisplayName("disbursed and past the due date: arrears on an active loan; awaiting disbursement past it is not"
            + " (nothing was lent); written off: a written-off balance")
    void arrears() {
        loan(1, StaffLoanStatus.DISBURSED, LocalDate.of(2026, 11, 20));
        loan(2, StaffLoanStatus.AWAITING_DISBURSEMENT, LocalDate.of(2026, 11, 20));
        loan(3, StaffLoanStatus.WRITTEN_OFF, LocalDate.of(2026, 6, 20));
        loan(4, StaffLoanStatus.DISBURSED, LocalDate.of(2026, 11, 23));

        assertThat(standing.of(List.of(member(1), member(2), member(3), member(4))))
                .isEqualTo(Map.of(1L, Set.of(Standing.ACTIVE_LOAN, Standing.ARREARS), 2L, Set.of(Standing.ACTIVE_LOAN),
                        3L, Set.of(Standing.WRITTEN_OFF), 4L, Set.of(Standing.ACTIVE_LOAN)));
    }

    @Test
    @DisplayName("the grace keeps a loan a few days past due out of arrears; every standing that applies is kept")
    void graceAndEveryStanding() {
        properties.setArrearsGraceDays(3);
        loan(1, StaffLoanStatus.DISBURSED, LocalDate.of(2026, 11, 20));
        loan(2, StaffLoanStatus.WRITTEN_OFF, LocalDate.of(2026, 6, 20));
        loan(2, StaffLoanStatus.AWAITING_DISBURSEMENT, LocalDate.of(2026, 12, 20));
        loan(3, StaffLoanStatus.WRITTEN_OFF, LocalDate.of(2026, 6, 20));
        loan(3, StaffLoanStatus.DISBURSED, LocalDate.of(2026, 11, 19));

        assertThat(standing.of(List.of(member(1), member(2), member(3))))
                .isEqualTo(Map.of(1L, Set.of(Standing.ACTIVE_LOAN),
                        2L, Set.of(Standing.WRITTEN_OFF, Standing.ACTIVE_LOAN),
                        3L, Set.of(Standing.WRITTEN_OFF, Standing.ACTIVE_LOAN, Standing.ARREARS)));
    }

    @Test
    @DisplayName("nobody asked about: no query")
    void empty() {
        assertThat(standing.of(List.of())).isEmpty();
        verify(repository, never()).findByStaffMemberIdInAndStatusIn(any(), any());
    }
}
