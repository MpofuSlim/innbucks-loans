package zw.co.innbucks.loans.core.instrument;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.IncompleteApplicationException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.files.DecodedFile;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static zw.co.innbucks.loans.core.audit.AuditService.sha256Hex;

/**
 * Signing the loan agreement and the SSB deduction authority (FR-SSB-013): what an application must carry once
 * wording is published, what is kept when it is signed, and how a record is checked when read.
 */
class SignedInstrumentServiceTest {

    private static final SigningContext SIGNING = new SigningContext("a3f1c2e4-7b9d-4e21", "10.0.12.34",
            "41.190.33.7", "InnBucksPortal/2.4", "pwd", "IN_PERSON_ID_CHECK");
    private static final DecodedFile SIGNATURE = new DecodedFile(new byte[]{1}, "image/png", "ab".repeat(32));
    private static final DecodedFile WITNESS = new DecodedFile(new byte[]{2}, "image/png", "cd".repeat(32));

    private InstrumentTemplateService templateService;
    private SignedInstrumentRepository instrumentRepository;
    private LoanRepository loanRepository;
    private SignedInstrumentService service;

    private static InstrumentTemplate template(InstrumentType type, int version, String body) {
        return InstrumentTemplate.builder().id((long) version).instrumentType(type).version(version)
                .title(type == InstrumentType.LOAN_AGREEMENT ? "SSB Loan Agreement" : "SSB Deduction Authority")
                .body(body).publishedBy("admin").publishedAt(LocalDateTime.of(2026, 9, 28, 12, 5)).build();
    }

    private static final InstrumentTemplate AGREEMENT_V3 = template(InstrumentType.LOAN_AGREEMENT, 3,
            "{{applicantName}} borrows USD {{principal}} over {{tenor}} months, signed {{signedDate}}.");
    private static final InstrumentTemplate AUTHORITY_V2 = template(InstrumentType.SSB_DEDUCTION_AUTHORITY, 2,
            "Deduct USD {{monthlyDeduction}} from EC {{ecNumber}} monthly.");

    @BeforeEach
    void setUp() {
        templateService = mock(InstrumentTemplateService.class);
        instrumentRepository = mock(SignedInstrumentRepository.class);
        loanRepository = mock(LoanRepository.class);
        when(instrumentRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new SignedInstrumentService(templateService, instrumentRepository, loanRepository,
                new MarketTimeZone("ZW"));
    }

    private void published(InstrumentTemplate... templates) {
        when(templateService.currentTemplate(any())).thenReturn(Optional.empty());
        for (InstrumentTemplate template : templates) {
            when(templateService.currentTemplate(template.getInstrumentType())).thenReturn(Optional.of(template));
        }
    }

    private static LoanApplicationRequest accepting(Integer agreement, Integer authority) {
        LoanApplicationRequest application = new LoanApplicationRequest();
        application.setLoanAgreementVersion(agreement);
        application.setDeductionAuthorityVersion(authority);
        return application;
    }

    private static Map<DocumentType, DecodedFile> signed(boolean withWitness) {
        Map<DocumentType, DecodedFile> documents = new EnumMap<>(DocumentType.class);
        documents.put(DocumentType.SIGNATURE, SIGNATURE);
        if (withWitness) {
            documents.put(DocumentType.WITNESS_SIGNATURE, WITNESS);
        }
        return documents;
    }

    @Test
    @DisplayName("with nothing published, an application needs no signing: no version, signature or device")
    void nothingPublished() {
        published();

        List<InstrumentTemplate> due = service.requireAccepted(accepting(null, null), Map.of(),
                new SigningContext(null, "10.0.12.34", null, null, "pwd", null));

        assertThat(due).isEmpty();
        assertThat(service.sign(Loan.builder().build(), due, Map.of(), SIGNING, "tmoyo")).isEmpty();
        verify(instrumentRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("once published, every gap is reported at once: each version, the signature, the device")
    void everyGapAtOnce() {
        published(AGREEMENT_V3, AUTHORITY_V2);

        assertThatThrownBy(() -> service.requireAccepted(accepting(null, null), Map.of(),
                new SigningContext(" ", "10.0.12.34", null, null, "pwd", null)))
                .isInstanceOfSatisfying(IncompleteApplicationException.class, e -> assertThat(e.getFields())
                        .containsOnlyKeys("loanAgreementVersion", "deductionAuthorityVersion", "signature", "X-Device-Id")
                        .containsEntry("loanAgreementVersion",
                                "The applicant must accept the loan agreement: version 3 is in force")
                        .containsEntry("signature", "The applicant's signature is required to sign the loan"
                                + " agreement and SSB deduction authority"));
    }

    @Test
    @DisplayName("a version sent for wording never published, a malformed device or signer authentication is refused")
    void malformedSigning() {
        published(AGREEMENT_V3);

        assertThatThrownBy(() -> service.requireAccepted(accepting(3, 1), signed(false),
                new SigningContext("has space", "10.0.12.34", null, null, "pwd", "no spaces!")))
                .isInstanceOfSatisfying(IncompleteApplicationException.class, e -> assertThat(e.getFields())
                        .containsOnlyKeys("deductionAuthorityVersion", "X-Device-Id", "X-Signer-Authentication")
                        .containsEntry("deductionAuthorityVersion",
                                "There is no published SSB deduction authority to accept"));
    }

    @Test
    @DisplayName("accepting a version no longer in force is a conflict: the applicant must see the new wording")
    void replacedWording() {
        published(AGREEMENT_V3, AUTHORITY_V2);

        assertThatThrownBy(() -> service.requireAccepted(accepting(2, 2), signed(false), SIGNING))
                .isInstanceOf(ConflictException.class)
                .hasMessage("The loan agreement has changed since the applicant accepted version 2: version 3 is in"
                        + " force. Show them version 3 and ask them to accept it.");
    }

    @Test
    @DisplayName("the versions in force, accepted with a signature and a device, are the instruments to sign")
    void accepted() {
        published(AGREEMENT_V3, AUTHORITY_V2);

        assertThat(service.requireAccepted(accepting(3, 2), signed(false), SIGNING))
                .containsExactly(AGREEMENT_V3, AUTHORITY_V2);
    }

    @Test
    @DisplayName("signing keeps the text as filled from the saved loan, its fingerprint, the signature and every piece of evidence, sealed")
    void signing() {
        Loan loan = InstrumentTermsTest.loan();
        loan.setId(43L);

        List<SignedInstrument> instruments = service.sign(loan, List.of(AGREEMENT_V3, AUTHORITY_V2), signed(true),
                SIGNING, "tmoyo");

        LocalDate today = new MarketTimeZone("ZW").today();
        SignedInstrument agreement = instruments.getFirst();
        assertThat(agreement.getLoanId()).isEqualTo(43L);
        assertThat(agreement.getInstrumentType()).isEqualTo(InstrumentType.LOAN_AGREEMENT);
        assertThat(agreement.getTemplateVersion()).isEqualTo(3);
        assertThat(agreement.getContent()).isEqualTo("Tatenda Ncube borrows USD 319.15 over 6 months, signed "
                + today + ".");
        assertThat(agreement.getContentSha256()).isEqualTo(sha256Hex(agreement.getContent()));
        assertThat(agreement.getSignatureSha256()).isEqualTo(SIGNATURE.sha256());
        assertThat(agreement.getWitnessSignatureSha256()).isEqualTo(WITNESS.sha256());
        assertThat(agreement.getSignedBy()).isEqualTo("tmoyo");
        assertThat(agreement.getSignedAt()).isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(5, ChronoUnit.SECONDS));
        assertThat(agreement.getSignedAt().getNano() % 1000).isZero();
        assertThat(agreement.getDeviceId()).isEqualTo("a3f1c2e4-7b9d-4e21");
        assertThat(agreement.getIpAddress()).isEqualTo("10.0.12.34");
        assertThat(agreement.getForwardedFor()).isEqualTo("41.190.33.7");
        assertThat(agreement.getUserAgent()).isEqualTo("InnBucksPortal/2.4");
        assertThat(agreement.getAuthenticationMethod()).isEqualTo("pwd");
        assertThat(agreement.getSignerAuthentication()).isEqualTo("IN_PERSON_ID_CHECK");
        assertThat(agreement.getEvidenceSha256()).isEqualTo(SignedInstrumentService.evidenceSha256(agreement));
        assertThat(instruments.get(1).getContent()).isEqualTo("Deduct USD 69.03 from EC 7654321B monthly.");
        assertThat(instruments.get(1).getSignedAt()).isEqualTo(agreement.getSignedAt());
    }

    @Test
    @DisplayName("what a client sends about itself is stored cleaned: control characters removed, cut to the column")
    void clientTextIsCleaned() {
        SigningContext noisy = new SigningContext("device-1", null, "  ", "Agent\r\nX-Injected: yes" + "x".repeat(600),
                null, null);

        SignedInstrument instrument = service.sign(InstrumentTermsTest.loan(), List.of(AUTHORITY_V2), signed(false),
                noisy, "tmoyo").getFirst();

        assertThat(instrument.getUserAgent()).startsWith("AgentX-Injected: yes").hasSize(512);
        assertThat(instrument.getForwardedFor()).isNull();
        assertThat(instrument.getIpAddress()).isEqualTo("unknown");
        assertThat(instrument.getAuthenticationMethod()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("the seal is a SHA-256 anyone can recompute from the fields, in the documented order")
    void sealRecipe() {
        SignedInstrument instrument = SignedInstrument.builder().loanId(43L)
                .instrumentType(InstrumentType.LOAN_AGREEMENT).templateVersion(3).title("SSB Loan Agreement")
                .contentSha256("c1").signatureSha256("s1").signedBy("tmoyo")
                .signedAt(LocalDateTime.of(2026, 9, 30, 7, 31, 15, 482_113_000)).deviceId("d1").ipAddress("10.0.12.34")
                .authenticationMethod("pwd").build();

        assertThat(SignedInstrumentService.evidenceSha256(instrument)).isEqualTo(sha256Hex(String.join("\u001F",
                "43", "LOAN_AGREEMENT", "3", "SSB Loan Agreement", "c1", "s1", "", "tmoyo",
                "2026-09-30T07:31:15.482113", "d1", "10.0.12.34", "", "", "pwd", "")));
        assertThat(SignedInstrumentService.evidenceSha256(SignedInstrument.builder().loanId(43L)
                .instrumentType(InstrumentType.LOAN_AGREEMENT).templateVersion(3).title("SSB Loan Agreement")
                .contentSha256("c1").signatureSha256("s1").signedBy("tmoyo")
                .signedAt(LocalDateTime.of(2026, 9, 30, 7, 31, 15)).deviceId("d1").ipAddress("10.0.12.34")
                .authenticationMethod("pwd").build()))
                .isEqualTo(sha256Hex(String.join("\u001F", "43", "LOAN_AGREEMENT", "3", "SSB Loan Agreement", "c1",
                        "s1", "", "tmoyo", "2026-09-30T07:31:15.000000", "d1", "10.0.12.34", "", "", "pwd", "")));
    }

    @Test
    @DisplayName("a record read back intact says so; one whose text or evidence was changed does not")
    void intactOnRead() {
        Loan loan = InstrumentTermsTest.loan();
        loan.setId(43L);
        List<SignedInstrument> instruments = service.sign(loan, List.of(AGREEMENT_V3, AUTHORITY_V2), signed(false),
                SIGNING, "tmoyo");
        SignedInstrument textChanged = instruments.get(0).toBuilder().content("Tatenda Ncube owes nothing.").build();
        SignedInstrument deviceChanged = instruments.get(1).toBuilder().deviceId("forged").build();
        when(loanRepository.existsById(43L)).thenReturn(true);
        when(instrumentRepository.findByLoanIdOrderByInstrumentType(43L))
                .thenReturn(List.of(instruments.get(0), textChanged, deviceChanged));

        List<SignedInstrumentResponse> read = service.forLoan(43L, LoanReadScope.platform());

        assertThat(read).extracting(SignedInstrumentResponse::intact).containsExactly(true, false, false);
        assertThat(read.getFirst().content()).isEqualTo(instruments.getFirst().getContent());
    }

    @Test
    @DisplayName("a loan the caller may not read is answered like one that does not exist")
    void unreadableLoan() {
        when(loanRepository.existsById(44L)).thenReturn(false);

        assertThatThrownBy(() -> service.forLoan(44L, LoanReadScope.platform()))
                .isInstanceOf(NotFoundException.class).hasMessage("Loan 44 not found");
        assertThatThrownBy(() -> service.forLoan(44L, LoanReadScope.originator("harare-motors", 7L)))
                .isInstanceOf(NotFoundException.class).hasMessage("Loan 44 not found");
        verify(instrumentRepository, never()).findByLoanIdOrderByInstrumentType(any());
    }

    @Test
    @DisplayName("a preview fills the wording in force as it would be signed today; nothing published, nothing to show")
    void preview() {
        published(AUTHORITY_V2);

        List<InstrumentPreview> previews = service.preview(InstrumentTermsTest.loan());

        assertThat(previews).singleElement().satisfies(preview -> {
            assertThat(preview.instrumentType()).isEqualTo(InstrumentType.SSB_DEDUCTION_AUTHORITY);
            assertThat(preview.version()).isEqualTo(2);
            assertThat(preview.content()).isEqualTo("Deduct USD 69.03 from EC 7654321B monthly.");
            assertThat(preview.contentSha256()).isEqualTo(sha256Hex(preview.content()));
        });
        published();
        assertThat(service.preview(InstrumentTermsTest.loan())).isEmpty();
    }

    @Test
    @DisplayName("the preview's text is exactly the text then signed, fingerprint for fingerprint")
    void previewIsWhatIsSigned() {
        published(AGREEMENT_V3, AUTHORITY_V2);
        Loan loan = InstrumentTermsTest.loan();

        List<InstrumentPreview> previews = service.preview(loan);
        loan.setId(43L);
        List<SignedInstrument> signed = service.sign(loan, List.of(AGREEMENT_V3, AUTHORITY_V2), signed(false), SIGNING,
                "tmoyo");

        assertThat(signed).extracting(SignedInstrument::getContentSha256)
                .containsExactlyElementsOf(previews.stream().map(InstrumentPreview::contentSha256).toList());
        ArgumentCaptor<List<SignedInstrument>> saved = ArgumentCaptor.captor();
        verify(instrumentRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
    }
}
