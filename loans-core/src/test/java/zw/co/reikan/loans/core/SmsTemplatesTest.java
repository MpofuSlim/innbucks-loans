package zw.co.reikan.loans.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.SmsMessages;
import zw.co.reikan.loans.core.user.AdminPasswordResetServiceImpl;
import zw.co.reikan.loans.core.user.CreateUserServiceImpl;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders every SMS this service sends with realistic arguments. Two rules:
 * the InnBucks SMS gateway refuses a body containing any of {@code ! : / ? " * ;}
 * (the fleet's SmsTextSanitizer whitelist, probed against the live gateway), and
 * no text names the customer's full mobile number. A template that breaks
 * either fails here rather than as an SMS nobody receives.
 */
class SmsTemplatesTest {

    private static final String GATEWAY_REFUSED = "!:/?\"*;";
    private static final String MOBILE = "+263782606983";
    /** The national significant number: present in every spelling of {@link #MOBILE}. */
    private static final String MOBILE_NSN = "782606983";
    private static final String REF = "000000042";
    private static final BigDecimal AMOUNT = new BigDecimal("1500.00");

    private static Loan disbursedLoan(String mobileNumber) {
        Loan loan = Loan.builder().mobileNumber(mobileNumber).disbursedAmount(AMOUNT).build();
        loan.setId(42L);
        return loan;
    }

    private static Map<String, String> everyRenderedSms() {
        Map<String, String> sms = new LinkedHashMap<>();
        sms.put("APPROVED_LOAN", String.format(SmsMessages.APPROVED_LOAN, REF, AMOUNT));
        sms.put("REJECTED_LOAN", String.format(SmsMessages.REJECTED_LOAN, REF, AMOUNT));
        sms.put("PROCESSING_LOAN", String.format(SmsMessages.PROCESSING_LOAN, REF, AMOUNT));
        // The SSB submission job formats with the reference alone.
        new LoanApprovalServiceJob(null, null, null, null).smsMessages
                .forEach((status, template) -> sms.put("job " + status, String.format(template, REF)));
        sms.put("SMS_MSG", DisbursementService.walletDisbursementSms(disbursedLoan(MOBILE)));
        sms.put("SMS_MSG_CONSUMER_FINANCE", String.format(DisbursementService.SMS_MSG_CONSUMER_FINANCE,
                AMOUNT, REF, "Bulawayo Furnishers (Pvt) Ltd"));
        sms.put("PASSWORD_SMS_TEMPLATE", String.format(CreateUserServiceImpl.PASSWORD_SMS_TEMPLATE,
                "Tariro", "tmoyo", "Kp7#Rw3@q"));
        sms.put("admin reset MESSAGE_TEMPLATE", String.format(AdminPasswordResetServiceImpl.MESSAGE_TEMPLATE,
                "Tariro", "tmoyo", "#K7mnpqrst"));
        return sms;
    }

    @Test
    @DisplayName("no SMS carries a character the gateway refuses")
    void noTemplateCarriesAGatewayRefusedCharacter() {
        everyRenderedSms().forEach((name, text) -> {
            for (char refused : GATEWAY_REFUSED.toCharArray()) {
                assertThat(text).as("%s must not contain '%s'", name, refused)
                        .doesNotContain(String.valueOf(refused));
            }
        });
    }

    @Test
    @DisplayName("no SMS names the customer's full mobile number")
    void noTemplateNamesTheFullMobileNumber() {
        everyRenderedSms().forEach((name, text) ->
                assertThat(text).as(name).doesNotContain(MOBILE_NSN));
    }

    @Test
    @DisplayName("the disbursement SMS names the wallet by its last four digits, whatever the spelling")
    void disbursementSmsNamesTheWalletByItsLastFourDigits() {
        for (String spelling : new String[]{"+263782606983", "263782606983", "0782606983", "782606983"}) {
            assertThat(DisbursementService.walletDisbursementSms(disbursedLoan(spelling)))
                    .as(spelling)
                    .isEqualTo("Your loan of $1500.00 with ref # 000000042 has been disbursed to your "
                            + "Innbucks wallet ending 6983. Welcome to the Innbucks family")
                    .doesNotContain(MOBILE_NSN);
        }
    }

    @Test
    @DisplayName("a decline carries no free text, even if a caller passes some")
    void declineCarriesNoFreeText() {
        String text = String.format(SmsMessages.REJECTED_LOAN, REF, AMOUNT,
                "Payslip looks edited; DTI 62pct");

        assertThat(text).isEqualTo("We regret to inform you that your loan application with ref # 000000042 "
                + "has been declined. Please contact Innbucks for more information.");
    }

    @Test
    @DisplayName("lastFourDigits ignores punctuation and never pads or throws")
    void lastFourDigits() {
        assertThat(MsisdnUtil.lastFourDigits("+263 78-260-6983")).isEqualTo("6983");
        assertThat(MsisdnUtil.lastFourDigits("983")).isEqualTo("983");
        assertThat(MsisdnUtil.lastFourDigits(null)).isEmpty();
    }
}
