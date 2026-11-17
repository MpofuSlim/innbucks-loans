package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.staff.StaffMember;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where each destination pays, from the register as it stands: a wallet is the borrower's registered phone; an
 * account is the register's account number, never a wallet the same field may hold instead.
 */
class StaffLoanPayoutDestinationTest {

    private static StaffMember member(String msisdn, String walletAccountNumber) {
        return StaffMember.builder().id(2L).msisdn(msisdn).walletAccountNumber(walletAccountNumber).build();
    }

    @Test
    @DisplayName("VEENGU_WALLET pays the registered phone, whatever the wallet-or-account field holds")
    void wallet() {
        assertThat(StaffLoanPayoutDestination.VEENGU_WALLET.accountOf(member("263772123123", "1234567890")))
                .isEqualTo("263772123123");
        assertThat(StaffLoanPayoutDestination.VEENGU_WALLET.accountOf(member("0772123123", "1234567890"))).isNull();
        assertThat(StaffLoanPayoutDestination.VEENGU_WALLET.accountOf(null)).isNull();
    }

    @Test
    @DisplayName("BR_ACCOUNT pays the register's account number, and none when that field holds a wallet")
    void account() {
        assertThat(StaffLoanPayoutDestination.BR_ACCOUNT.accountOf(member("263772123123", "1234567890")))
                .isEqualTo("1234567890");
        assertThat(StaffLoanPayoutDestination.BR_ACCOUNT.accountOf(member("263772123123", "123456")))
                .isEqualTo("123456");
        assertThat(StaffLoanPayoutDestination.BR_ACCOUNT.accountOf(member("263772123123", "263772123123"))).isNull();
        assertThat(StaffLoanPayoutDestination.BR_ACCOUNT.accountOf(member("263772123123", "12345"))).isNull();
        assertThat(StaffLoanPayoutDestination.BR_ACCOUNT.accountOf(member("263772123123", "123456789012345678901")))
                .isNull();
        assertThat(StaffLoanPayoutDestination.BR_ACCOUNT.accountOf(member("263772123123", null))).isNull();
    }

    @Test
    @DisplayName("how the borrower is told where the money goes")
    void labels() {
        assertThat(StaffLoanPayoutDestination.VEENGU_WALLET.label()).isEqualTo("InnBucks wallet");
        assertThat(StaffLoanPayoutDestination.BR_ACCOUNT.label()).isEqualTo("InnBucks account");
    }
}
