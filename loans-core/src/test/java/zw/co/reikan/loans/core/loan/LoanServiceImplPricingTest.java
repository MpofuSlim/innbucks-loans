package zw.co.reikan.loans.core.loan;

import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.channel.ChannelRepository;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionStructure;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.parameter.ParameterService;
import zw.co.reikan.loans.core.user.User;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static zw.co.reikan.loans.core.loan.Constants.*;

/**
 * Pins {@link LoanServiceImpl#calculate} against figures worked by hand. The
 * configured rates are PERCENTS, and converting one to a fraction used to round
 * the fraction to two decimals: 2.5% priced as 3%, 3.75% as 4%, and anything
 * under 0.5% as 0% (a division by zero). The instalment, the schedule, the
 * grossed deduction lodged with Ndasenda and the InnBucks booking all followed.
 *
 * <p>Every instalment below is the annuity {@code P*r*(1+r)^n / ((1+r)^n - 1)}
 * evaluated exactly and rounded HALF_UP to the cent; each schedule row is
 * {@code interest = round(balance * r)}, {@code principal = instalment - interest}.
 */
class LoanServiceImplPricingTest {

    private ParameterService parameters;
    private LoanServiceImpl service;

    @BeforeEach
    void setUp() {
        parameters = mock(ParameterService.class);
        service = new LoanServiceImpl(mock(LoanRepository.class), parameters, mock(LoanMapper.class),
                mock(AuthService.class), mock(MerchantRepository.class), mock(ChannelRepository.class),
                mock(Validator.class));
    }

    private void rates(String monthlyInterest, String commission, String adminFee) {
        when(parameters.getParameterValues(any(String[].class))).thenReturn(Map.of(
                COMMISSION_RATE, commission, ADMI_FEE_RATE, adminFee, MONTHLY_INTEREST_RATE, monthlyInterest,
                AGENT_COMMISSION_RATE, "0", MINIMUM_LOAN_AMOUNT, "50", MAXIMUM_LOAN_AMOUNT, "5000",
                MINIMUM_LOAN_TENOR, "1", MAXIMUM_LOAN_TENOR, "24"));
    }

    private static LoanRequest request(String amount, LoanAmountType type) {
        return LoanRequest.builder().amount(new BigDecimal(amount)).tenor(12).type(type).build();
    }

    @Test
    @DisplayName("2.5% a month prices at 2.5%, not 3%: 1000.00 over 12 months is 97.49 a month")
    void fractionalMonthlyRateIsNotRoundedUp() {
        rates("2.5", "0", "5");

        // (1.025)^12 = 1.3448888242...; 1000 * 0.025 * 1.34488... / 0.34488... = 97.4871...
        // At the old 3% it was 100.46 — 2.97 a month, 35.64 over the loan, overcharged.
        LoanDetails details = service.calculate(request("1000.00", LoanAmountType.GROSS_OF_FEES), null);

        assertThat(details.getRegularMonthlyInstallment()).isEqualTo(new BigDecimal("97.49"));
        assertThat(details.getAdminFeeAmount()).isEqualTo(new BigDecimal("50.00"));
        assertThat(details.getDisbursedAmount()).isEqualTo(new BigDecimal("950.00"));

        List<AmortizationEntry> schedule = details.getAmortization();
        assertThat(schedule).hasSize(12);
        // Row 1: 1000.00 * 0.025 = 25.00 interest (30.00 at the old 3%);
        // 97.49 - 25.00 = 72.49 principal; 1000.00 - 72.49 = 927.51 left.
        AmortizationEntry first = schedule.get(0);
        assertThat(first.getInterestPayment()).isEqualTo(new BigDecimal("25.00"));
        assertThat(first.getPrincipalPayment()).isEqualTo(new BigDecimal("72.49"));
        assertThat(first.getRemainingPrincipal()).isEqualTo(new BigDecimal("927.51"));
        // Row 2: 927.51 * 0.025 = 23.18775 -> 23.19; 97.49 - 23.19 = 74.30; 853.21 left.
        AmortizationEntry second = schedule.get(1);
        assertThat(second.getInterestPayment()).isEqualTo(new BigDecimal("23.19"));
        assertThat(second.getPrincipalPayment()).isEqualTo(new BigDecimal("74.30"));
        assertThat(second.getRemainingPrincipal()).isEqualTo(new BigDecimal("853.21"));
        // The schedule amortises to zero and its interest column sums to 169.84.
        assertThat(schedule.get(11).getRemainingPrincipal()).isEqualByComparingTo("0");
        assertThat(details.getInterestAmount()).isEqualTo(new BigDecimal("169.84"));
    }

    @Test
    @DisplayName("a whole-percent rate prices exactly as before: 3% on 1000.00 over 12 months is 100.46")
    void wholeMonthlyRateIsUnchanged() {
        rates("3", "0", "5");

        // (1.03)^12 = 1.4257608868...; 1000 * 0.03 * 1.42576... / 0.42576... = 100.4620...
        LoanDetails details = service.calculate(request("1000.00", LoanAmountType.GROSS_OF_FEES), null);

        assertThat(details.getRegularMonthlyInstallment()).isEqualTo(new BigDecimal("100.46"));
        // Row 1: 1000.00 * 0.03 = 30.00; 100.46 - 30.00 = 70.46; 929.54 left.
        AmortizationEntry first = details.getAmortization().get(0);
        assertThat(first.getInterestPayment()).isEqualTo(new BigDecimal("30.00"));
        assertThat(first.getPrincipalPayment()).isEqualTo(new BigDecimal("70.46"));
        assertThat(first.getRemainingPrincipal()).isEqualTo(new BigDecimal("929.54"));
        assertThat(details.getInterestAmount()).isEqualTo(new BigDecimal("205.57"));
    }

    @Test
    @DisplayName("a rate under half a percent prices instead of rounding to zero and dividing by it")
    void subHalfPercentRatePrices() {
        rates("0.4", "0", "5");

        // (1.004)^12 - 1 = 0.0490...; 1000 * 0.004 * 1.0490... / 0.0490... = 85.5215...
        // The old code rounded 0.004 to 0.00 and threw on (1 + 0)^12 - 1 = 0.
        LoanDetails details = service.calculate(request("1000.00", LoanAmountType.GROSS_OF_FEES), null);

        assertThat(details.getRegularMonthlyInstallment()).isEqualTo(new BigDecimal("85.52"));
        assertThat(details.getAmortization().get(0).getInterestPayment()).isEqualTo(new BigDecimal("4.00"));
    }

    @Test
    @DisplayName("a fractional commission grosses the deduction and splits the commission at the real rate")
    void fractionalCommissionRate() {
        rates("3", "2.5", "5");
        Merchant merchant = Merchant.builder().commissionStructure(CommissionStructure.MERCHANT_DEFINED)
                .commissionGroup(CommissionGroup.builder().percentage(true)
                        .agentCommission(new BigDecimal("37.5")).providerCommission(new BigDecimal("62.5")).build())
                .build();
        User agent = new User();
        agent.setMerchant(merchant);

        // 3% on 1200.00 over 12 months: 1200 * 0.03 * 1.42576... / 0.42576... = 120.5545... -> 120.55.
        LoanDetails details = service.calculate(request("1200.00", LoanAmountType.GROSS_OF_FEES), agent);

        assertThat(details.getRegularMonthlyInstallment()).isEqualTo(new BigDecimal("120.55"));
        // Grossed deduction = 120.55 / (1 - 0.025) = 123.6410... -> 123.64
        // (at the old 3% it was 120.55 / 0.97 = 124.28).
        assertThat(details.getGrossedMonthlyInstallment()).isEqualTo(new BigDecimal("123.64"));
        // Commission = 1200.00 * 0.025 = 30.00, split 37.5% / 62.5% = 11.25 / 18.75 — exactly
        // the whole. The old code took 3% (36.00) and split it 38% / 63% (13.68 / 22.68),
        // paying out 101% of a commission that was itself overstated.
        assertThat(details.getAgentCommission()).isEqualTo(new BigDecimal("11.25"));
        assertThat(details.getProviderCommission()).isEqualTo(new BigDecimal("18.75"));
    }

    @Test
    @DisplayName("net of a fractional fee, the customer is disbursed exactly the amount asked for")
    void netOfFractionalFee() {
        rates("3", "0", "2.5");

        // 975.00 / (1 - 0.025) = 1000.00 principal; fee 1000.00 * 2.5 / 100 = 25.00.
        LoanDetails details = service.calculate(request("975.00", LoanAmountType.NET_OF_FEES), null);

        assertThat(details.getPrincipal()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(details.getAdminFeeAmount()).isEqualTo(new BigDecimal("25.00"));
        assertThat(details.getDisbursedAmount()).isEqualTo(new BigDecimal("975.00"));
    }
}
