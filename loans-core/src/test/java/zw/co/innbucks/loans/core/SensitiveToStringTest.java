package zw.co.innbucks.loans.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.api.LoginRequest;
import zw.co.innbucks.loans.core.api.LoginResponse;
import zw.co.innbucks.loans.core.api.CreateUserRequest;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.auth.JwtProperties;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.disbursements.InnbucksAuthRequest;
import zw.co.innbucks.loans.core.disbursements.InnbucksAuthResponse;
import zw.co.innbucks.loans.core.disbursements.InnbucksParameters;
import zw.co.innbucks.loans.core.disbursements.LoanAccountCreationRequest;
import zw.co.innbucks.loans.core.document.AmendDocumentRequest;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.LoanDocument;
import zw.co.innbucks.loans.core.document.LoanDocumentContent;
import zw.co.innbucks.loans.core.draft.DraftDocumentContent;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraft;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraftDocument;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraftStatus;
import zw.co.innbucks.loans.core.employment.EmploymentEvent;
import zw.co.innbucks.loans.core.employment.EmploymentEventType;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEvent;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventAction;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplate;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.instrument.SignedInstrument;
import zw.co.innbucks.loans.core.loan.BankingDetail;
import zw.co.innbucks.loans.core.loan.Customer;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanResponse;
import zw.co.innbucks.loans.core.loan.NextOfKin;
import zw.co.innbucks.loans.core.loan.Witness;
import zw.co.innbucks.loans.core.ndasenda.LoanApprovalRequest;
import zw.co.innbucks.loans.core.ndasenda.NdasendaAuthResponse;
import zw.co.innbucks.loans.core.ndasenda.NdasendaDeduction;
import zw.co.innbucks.loans.core.ndasenda.NdasendaDeductionBatch;
import zw.co.innbucks.loans.core.ndasenda.NdasendaParameters;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotification;
import zw.co.innbucks.loans.core.notice.OutgoingNotice;
import zw.co.innbucks.loans.core.notifications.InnbucksNotifyProperties;
import zw.co.innbucks.loans.core.notifications.WhatsAppProperties;
import zw.co.innbucks.loans.core.user.NewUser;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.util.Base64;
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
    @DisplayName("LoanApplicationRequest prints no national ID, next-of-kin ID, bank details, images or signature")
    void loanRequestHidesKycAndDocuments() {
        LoanApplicationRequest request = LoanApplicationRequest.builder()
                .amount(new BigDecimal("500"))
                .ecNumber("1234567A")
                .tenor(12)
                .nationalIdNumber(NATIONAL_ID)
                .nextOfKin(nextOfKin())
                .witness(witness())
                .bankingDetail(bankingDetail())
                .signature(SIGNATURE)
                .nationalIdPicture(IMAGE)
                .payslipPicture(IMAGE)
                .channelId("superapp")
                .build();

        assertNoPii(request);
        assertThat(request.toString()).contains("amount=500", "tenor=12", "superapp", "Rudo", "T. Moyo");
    }

    @Test
    @DisplayName("Loan and LoanResponse print none of the applicant's KYC, documents or the creator's password hash")
    void loanHidesKycAndDocuments() {
        Loan loan = new Loan();
        loan.setEcNumber("1234567A");
        loan.setNationalIdNumber(NATIONAL_ID);
        loan.setBankingDetail(bankingDetail());
        loan.setNextOfKin(nextOfKin());
        loan.setWitness(witness());
        loan.setCreatedByUser(user("creator-1"));

        LoanResponse dto = new LoanResponse();
        dto.setNationalIdNumber(NATIONAL_ID);
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
    @DisplayName("a stored document, its content view and an amendment print no content (FR-SSB-009)")
    void documentsPrintNoContent() {
        byte[] bytes = Base64.getDecoder().decode(IMAGE);
        LoanDocument document = LoanDocument.builder().loanId(42L).documentType(DocumentType.NATIONAL_ID).version(2)
                .origin(DocumentOrigin.AMENDMENT).content(bytes).contentType("image/png").sizeBytes(bytes.length)
                .sha256("ab".repeat(32)).reason("Clearer copy").uploadedBy("agent.moyo").build();
        AmendDocumentRequest amendment = new AmendDocumentRequest(SIGNATURE, "Clearer copy");

        assertNoPii(document);
        assertNoPii(LoanDocumentContent.of(document));
        assertNoPii(amendment);
        assertThat(LoanDocumentContent.of(document).content()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(LoanDocumentContent.of(document).toString()).contains("NATIONAL_ID", "version=2");
        assertThat(amendment.toString()).contains("Clearer copy");
    }

    @Test
    @DisplayName("a saved draft, its documents and a document's content view print no KYC or content (FR-SSB-002)")
    void draftsPrintNoKycOrContent() {
        byte[] bytes = Base64.getDecoder().decode(IMAGE);
        LoanApplicationDraft draft = LoanApplicationDraft.builder().id(7L).ownerUserId(5L)
                .status(LoanApplicationDraftStatus.OPEN)
                .application("{\"nationalIdNumber\":\"" + NATIONAL_ID + "\",\"firstName\":\"Rudo\"}").build();
        LoanApplicationDraftDocument document = LoanApplicationDraftDocument.builder().draftId(7L)
                .documentType(DocumentType.NATIONAL_ID).content(bytes).contentType("image/png").sizeBytes(bytes.length)
                .sha256("ab".repeat(32)).build();
        DraftDocumentContent content = new DraftDocumentContent(DocumentType.NATIONAL_ID, "image/png", bytes.length,
                "ab".repeat(32), null, IMAGE);

        assertNoPii(draft);
        assertNoPii(document);
        assertNoPii(content);
        assertThat(draft.toString()).contains("id=7", "OPEN");
        assertThat(content.toString()).contains("NATIONAL_ID");
    }

    @Test
    @DisplayName("a signed instrument and its wording print neither the signed text nor where it was signed (FR-SSB-013)")
    void signedInstrumentsPrintNoTextOrDevice() {
        SignedInstrument instrument = SignedInstrument.builder().id(1L).loanId(43L)
                .instrumentType(InstrumentType.LOAN_AGREEMENT).templateVersion(3)
                .content("Tatenda Ncube, national ID " + NATIONAL_ID + ", borrows USD 319.15")
                .deviceId("device-a3f1c2e4").ipAddress("41.190.33.7").forwardedFor("41.190.33.7")
                .userAgent("InnBucksPortal/2.4").signedBy("tmoyo").build();
        InstrumentTemplate template = InstrumentTemplate.builder().instrumentType(InstrumentType.LOAN_AGREEMENT)
                .version(3).title("SSB Loan Agreement").body("Borrower " + NATIONAL_ID).build();

        assertNoPii(instrument);
        assertNoPii(template);
        assertThat(instrument.toString()).doesNotContain("device-a3f1c2e4", "41.190.33.7", "InnBucksPortal")
                .contains("loanId=43", "LOAN_AGREEMENT", "templateVersion=3");
        assertThat(template.toString()).contains("version=3");
    }

    @Test
    @DisplayName("an employment event prints neither the EC number nor the officer's note (FR-SSB-024)")
    void employmentEventsPrintNoEcNumberOrNote() {
        EmploymentEvent event = EmploymentEvent.builder().id(5L).ecNumber("1234567A")
                .eventType(EmploymentEventType.DEATH_IN_SERVICE).note("Reported by the next of kin, " + NATIONAL_ID)
                .build();
        LoanEmploymentEvent loanEvent = LoanEmploymentEvent.builder().id(11L).eventId(5L).loanId(42L)
                .action(LoanEmploymentEventAction.REVIEW).comment("Claim lodged for " + NATIONAL_ID).build();

        assertThat(event.toString()).doesNotContain("1234567A", NATIONAL_ID, "next of kin")
                .contains("id=5", "DEATH_IN_SERVICE");
        assertThat(loanEvent.toString()).doesNotContain(NATIONAL_ID).contains("loanId=42", "REVIEW");
    }

    @Test
    @DisplayName("a notice to the applicant prints neither their number nor the text (FR-SSB-016)")
    void noticesPrintNoRecipientOrText() {
        LoanNotification notification = LoanNotification.builder().id(1L).loanId(43L).notice(LoanNotice.APPROVED)
                .channel(LoanNotification.SMS).recipient("+263771234567")
                .message("Your loan application with ref # 000000043 has been approved").sent(true)
                .gatewayReference("LOANS-SMS-1").build();
        OutgoingNotice outgoing = new OutgoingNotice(43L, LoanNotice.APPROVED, "+263771234567",
                "Your loan application with ref # 000000043 has been approved", "LOANS-SMS-1");

        for (Object notice : List.of(notification, outgoing)) {
            assertThat(notice.toString()).doesNotContain("263771234567", "has been approved")
                    .contains("43", "APPROVED", "LOANS-SMS-1");
        }
    }

    @Test
    @DisplayName("user-management requests print neither a password hash nor any ID number")
    void userRequestsHideIdNumbersAndHashes() {
        NewUser newUser = NewUser.builder()
                .username("new-user")
                .idNumber(NATIONAL_ID)
                .build();
        UserResponse userResponse = UserResponse.builder()
                .username("new-user")
                .idNumber(NATIONAL_ID)
                .build();
        CreateUserRequest createUserRequest = new CreateUserRequest();
        createUserRequest.setIdNumber(NATIONAL_ID);

        assertNoPii(newUser);
        assertNoPii(userResponse);
        assertNoPii(createUserRequest);
        assertThat(newUser.toString()).contains("new-user");
        assertThat(userResponse.toString()).contains("new-user");
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
        NdasendaDeductionBatch batch = NdasendaDeductionBatch.builder()
                .id("batch-42")
                .securityToken(secret)
                .deductions(List.of(NdasendaDeduction.builder().reference("000000042").idNumber(NATIONAL_ID).build()))
                .build();
        LoanApprovalRequest approval = LoanApprovalRequest.builder()
                .reference("000000042").ecnumber("1234567A").idNumber(NATIONAL_ID).build();
        LoanAccountCreationRequest application = LoanAccountCreationRequest.builder()
                .participantReference("000000042").idNumber(NATIONAL_ID).nextOfKinIdNumber(NEXT_OF_KIN_ID).build();
        LoginRequest login = new LoginRequest();
        login.setUsername("admin");
        login.setPassword(secret);
        LoginResponse issued = LoginResponse.builder().accessToken(secret).tokenType("Bearer").build();

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
