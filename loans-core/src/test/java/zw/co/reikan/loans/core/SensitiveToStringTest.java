package zw.co.reikan.loans.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.reikan.loans.core.api.AuthRequest;
import zw.co.reikan.loans.core.api.AuthResponse;
import zw.co.reikan.loans.core.api.CreateAgentRequest;
import zw.co.reikan.loans.core.api.CreateUserRequest;
import zw.co.reikan.loans.core.api.UserDTO;
import zw.co.reikan.loans.core.auth.JwtProperties;
import zw.co.reikan.loans.core.channel.Channel;
import zw.co.reikan.loans.core.disbursements.InnbucksAuthRequest;
import zw.co.reikan.loans.core.disbursements.InnbucksAuthResponse;
import zw.co.reikan.loans.core.disbursements.InnbucksParameters;
import zw.co.reikan.loans.core.disbursements.LoanAccountCreationRequest;
import zw.co.reikan.loans.core.loan.BankingDetail;
import zw.co.reikan.loans.core.loan.Customer;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanDto;
import zw.co.reikan.loans.core.loan.LoanRequest;
import zw.co.reikan.loans.core.loan.NextOfKin;
import zw.co.reikan.loans.core.loan.Witness;
import zw.co.reikan.loans.core.ndasenda.LoanApprovalRequest;
import zw.co.reikan.loans.core.ndasenda.NdasendaAuthResponse;
import zw.co.reikan.loans.core.ndasenda.NdasendaDeduction;
import zw.co.reikan.loans.core.ndasenda.NdasendaDeductionsBatchRequest;
import zw.co.reikan.loans.core.ndasenda.NdasendaParameters;
import zw.co.reikan.loans.core.notifications.InnbucksNotifyProperties;
import zw.co.reikan.loans.core.notifications.WhatsAppProperties;
import zw.co.reikan.loans.core.user.User;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lombok's generated {@code toString} is what a {@code log.info("...{}", object)} prints,
 * so these types must not carry a password hash, a credential, a national ID, bank
 * details or a base64 document in it — whoever logs them next.
 */
class SensitiveToStringTest {

    private static final String PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final String NATIONAL_ID = "63-123456A63";
    private static final String NEXT_OF_KIN_ID = "08-765432B08";
    private static final String ACCOUNT_NUMBER = "1100223344556";
    private static final String IMAGE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk";
    private static final String SIGNATURE = "data:image/png;base64,R0lGODlhAQABAIAAAAAAAP";

    private static User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(PASSWORD_HASH);
        user.setIdNumber(NATIONAL_ID);
        user.setFirstName("Tariro");
        return user;
    }

    private static NextOfKin nextOfKin() {
        NextOfKin nextOfKin = new NextOfKin();
        nextOfKin.setFirstName("Rudo");
        nextOfKin.setNationalId(NEXT_OF_KIN_ID);
        return nextOfKin;
    }

    private static Witness witness() {
        Witness witness = new Witness();
        witness.setFullName("T. Moyo");
        witness.setSignature(SIGNATURE);
        return witness;
    }

    private static BankingDetail bankingDetail() {
        BankingDetail bankingDetail = new BankingDetail();
        bankingDetail.setBankName("CBZ");
        bankingDetail.setAccountNumber(ACCOUNT_NUMBER);
        return bankingDetail;
    }

    private static void assertNoPii(Object value) {
        assertThat(value.toString()).doesNotContain(PASSWORD_HASH, NATIONAL_ID, NEXT_OF_KIN_ID, ACCOUNT_NUMBER,
                IMAGE, SIGNATURE);
    }

    @Test
    @DisplayName("User never prints its password hash or ID number — nor does a channel holding one")
    void userHidesPasswordHash() {
        User user = user("user-1");
        Channel channel = Channel.builder().channelId("superapp").name("Mobile").systemUser(user).build();

        assertNoPii(user);
        assertNoPii(channel);
        assertThat(user.toString()).contains("user-1", "Tariro");
        assertThat(channel.toString()).contains("Mobile", "user-1");
    }

    @Test
    @DisplayName("LoanRequest prints no national ID, next-of-kin ID, bank details, images or signature")
    void loanRequestHidesKycAndDocuments() {
        LoanRequest request = LoanRequest.builder()
                .amount(new BigDecimal("500"))
                .ecnumber("1234567A")
                .tenor(12)
                .nationalId(NATIONAL_ID)
                .nextOfKin(nextOfKin())
                .nextOfKinIdNumber(NEXT_OF_KIN_ID)
                .witness(witness())
                .bankingDetail(bankingDetail())
                .signatureData(SIGNATURE)
                .nationalIdPicture(IMAGE)
                .payslipPicture(IMAGE)
                .channelId("superapp")
                .build();

        assertNoPii(request);
        assertThat(request.toString()).contains("amount=500", "tenor=12", "superapp", "Rudo", "T. Moyo");
    }

    @Test
    @DisplayName("Loan and LoanDto print none of the applicant's KYC, documents or the creator's password hash")
    void loanHidesKycAndDocuments() {
        Loan loan = new Loan();
        loan.setEcNumber("1234567A");
        loan.setNationalIdNumber(NATIONAL_ID);
        loan.setSignature(SIGNATURE);
        loan.setNationalIdPicture(IMAGE);
        loan.setPayslipPicture(IMAGE);
        loan.setBankingDetail(bankingDetail());
        loan.setNextOfKin(nextOfKin());
        loan.setWitness(witness());
        loan.setCreatedByUser(user("creator-1"));

        LoanDto dto = new LoanDto();
        dto.setNationalIdNumber(NATIONAL_ID);
        dto.setSignature(SIGNATURE);
        dto.setNationalIdPicture(IMAGE);
        dto.setPayslipPicture(IMAGE);
        dto.setBankingDetail(bankingDetail());
        dto.setNextOfKin(nextOfKin());
        dto.setWitness(witness());

        Customer customer = new Customer();
        customer.setNationalIdNumber(NATIONAL_ID);
        customer.setSignature(SIGNATURE);

        assertNoPii(loan);
        assertNoPii(dto);
        assertNoPii(customer);
        assertNoPii(bankingDetail());
        assertThat(loan.toString()).contains("1234567A", "creator-1");
    }

    @Test
    @DisplayName("user-management requests print neither a password hash nor any ID number")
    void userRequestsHideIdNumbersAndHashes() {
        CreateUserRequest createUserRequest = CreateUserRequest.builder()
                .username("new-user")
                .idNumber(NATIONAL_ID)
                .build();
        UserDTO userDTO = new UserDTO();
        userDTO.setUsername("new-user");
        userDTO.setPassword(PASSWORD_HASH);
        userDTO.setIdNumber(NATIONAL_ID);
        CreateAgentRequest createAgentRequest = new CreateAgentRequest();
        createAgentRequest.setIdNumber(NATIONAL_ID);

        assertNoPii(createUserRequest);
        assertNoPii(userDTO);
        assertNoPii(createAgentRequest);
        assertThat(createUserRequest.toString()).contains("new-user");
    }

    @Test
    @DisplayName("integration requests and answers print no credential, token or national ID")
    void integrationDtosHideCredentials() {
        String secret = "secret-value-1";
        InnbucksAuthRequest innbucksLogin = InnbucksAuthRequest.builder().username("svc").password(secret).build();
        InnbucksAuthResponse innbucksToken = new InnbucksAuthResponse();
        innbucksToken.setAccessToken(secret);
        NdasendaAuthResponse ndasendaToken = new NdasendaAuthResponse();
        ndasendaToken.setAccessToken(secret);
        ndasendaToken.setRefreshToken(secret);
        NdasendaDeductionsBatchRequest batch = NdasendaDeductionsBatchRequest.builder()
                .id("batch-42")
                .securityToken(secret)
                .deductions(List.of(NdasendaDeduction.builder().reference("000000042").idNumber(NATIONAL_ID).build()))
                .build();
        LoanApprovalRequest approval = LoanApprovalRequest.builder()
                .reference("000000042").ecnumber("1234567A").idNumber(NATIONAL_ID).build();
        LoanAccountCreationRequest application = LoanAccountCreationRequest.builder()
                .participantReference("000000042").idNumber(NATIONAL_ID).nextOfKinIdNumber(NEXT_OF_KIN_ID).build();
        AuthRequest login = new AuthRequest();
        login.setUsername("admin");
        login.setPassword(secret);
        AuthResponse issued = AuthResponse.builder().accessToken(secret).tokenType("Bearer").build();

        for (Object dto : List.of(innbucksLogin, innbucksToken, ndasendaToken, batch, approval, application,
                login, issued)) {
            assertThat(dto.toString()).doesNotContain(secret, NATIONAL_ID, NEXT_OF_KIN_ID);
        }
        assertThat(batch.toString()).contains("batch-42");
        assertThat(approval.toString()).contains("000000042", "ecnumber=*****67A").doesNotContain("1234567A");
    }

    @Test
    @DisplayName("configuration properties print no password, API key, security code or signing secret")
    void propertiesHideSecrets() {
        String secret = "secret-value-2";
        NdasendaParameters ndasenda = new NdasendaParameters();
        ndasenda.setUsername("svc");
        ndasenda.setPassword(secret);
        ndasenda.setSecurityCode(secret);
        InnbucksParameters innbucks = new InnbucksParameters();
        innbucks.setPassword(secret);
        innbucks.setApiKey(secret);
        InnbucksNotifyProperties notify = new InnbucksNotifyProperties();
        notify.setPassword(secret);
        notify.setApiKey(secret);
        WhatsAppProperties whatsApp = new WhatsAppProperties();
        whatsApp.setApiKey(secret);
        JwtProperties jwt = new JwtProperties();
        jwt.setSecret(secret);

        for (Object properties : List.of(ndasenda, innbucks, notify, whatsApp, jwt)) {
            assertThat(properties.toString()).doesNotContain(secret);
        }
        assertThat(ndasenda.toString()).contains("svc");
    }
}
