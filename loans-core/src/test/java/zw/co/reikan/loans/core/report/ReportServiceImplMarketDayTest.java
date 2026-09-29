package zw.co.reikan.loans.core.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.reikan.loans.core.config.MarketTimeZone;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.merchant.MerchantRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Reports cut their days at UTC midnight, so a loan paid at 01:00 in Harare was counted on the day
 * before, and on the 1st, in the month before. Their days are now the market's.
 */
class ReportServiceImplMarketDayTest {

    private final LoanRepository loans = mock(LoanRepository.class);

    private ReportServiceImpl reports(Clock clock) {
        return new ReportServiceImpl(loans, mock(MerchantRepository.class), new MarketTimeZone("ZW", clock));
    }

    @Test
    @DisplayName("a report of 1 October covers 1 October in Harare: 22:00 UTC on the 30th to 21:59:59 UTC")
    void dayBoundsAreTheMarkets() {
        var response = reports(Clock.systemUTC()).disbursementsReport(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), null);

        verify(loans).disbursementsByDay(LocalDateTime.of(2026, 9, 30, 22, 0),
                LocalDateTime.of(2026, 10, 1, 21, 59, 59, 999_999_999), null, "Africa/Harare");
        assertThat(response.fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("month-to-date is the market's month: at 00:30 on 1 October in Harare it is October")
    void defaultPeriodIsTheMarketsMonth() {
        Clock justAfterMidnightInHarare = Clock.fixed(Instant.parse("2026-09-30T22:30:00Z"), ZoneOffset.UTC);

        var response = reports(justAfterMidnightInHarare).disbursementsReport(null, null, null);

        assertThat(response.fromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(response.toDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        verify(loans).disbursementsByDay(LocalDateTime.of(2026, 9, 30, 22, 0),
                LocalDateTime.of(2026, 10, 1, 21, 59, 59, 999_999_999), null, "Africa/Harare");
    }
}
