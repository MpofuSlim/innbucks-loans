package zw.co.innbucks.loans.core.instrument;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Publishing instrument wording (FR-SSB-013): each publication is the next version, never a change to one. */
class InstrumentTemplateServiceTest {

    private InstrumentTemplateRepository templateRepository;
    private LoanRepository loanRepository;
    private InstrumentTemplateService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(InstrumentTemplateRepository.class);
        loanRepository = mock(LoanRepository.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("admin");
        when(templateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new InstrumentTemplateService(templateRepository, loanRepository, authService);
    }

    @Test
    @DisplayName("new wording is the next version, published by the caller, with the instrument locked while it is numbered")
    void publishesTheNextVersion() {
        when(templateRepository.findLatestVersion(InstrumentType.LOAN_AGREEMENT)).thenReturn(2);

        InstrumentTemplateResponse published = service.publish(new PublishInstrumentTemplateRequest(
                InstrumentType.LOAN_AGREEMENT, "  SSB Loan Agreement ", "{{applicantName}} borrows USD {{principal}}."));

        assertThat(published.version()).isEqualTo(3);
        assertThat(published.title()).isEqualTo("SSB Loan Agreement");
        assertThat(published.body()).isEqualTo("{{applicantName}} borrows USD {{principal}}.");
        assertThat(published.publishedBy()).isEqualTo("admin");
        assertThat(published.publishedAt()).isNotNull();
        InOrder order = inOrder(loanRepository, templateRepository);
        order.verify(loanRepository).lockApplicant("instrument-template:LOAN_AGREEMENT");
        order.verify(templateRepository).findLatestVersion(InstrumentType.LOAN_AGREEMENT);
        order.verify(templateRepository).save(any());
    }

    @Test
    @DisplayName("an instrument never published starts at version 1")
    void firstVersion() {
        when(templateRepository.findLatestVersion(InstrumentType.SSB_DEDUCTION_AUTHORITY)).thenReturn(0);

        assertThat(service.publish(new PublishInstrumentTemplateRequest(InstrumentType.SSB_DEDUCTION_AUTHORITY,
                "SSB Deduction Authority", "Deduct USD {{monthlyDeduction}}.")).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("wording naming a placeholder that is not a loan term is refused, and nothing is published")
    void unknownPlaceholderRefused() {
        assertThatThrownBy(() -> service.publish(new PublishInstrumentTemplateRequest(InstrumentType.LOAN_AGREEMENT,
                "SSB Loan Agreement", "Pay {{salary}} to {{applicantName}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown placeholder {{salary}}: a placeholder must be one of the loan terms listed by"
                        + " GET /instrument-templates/placeholders");
        verify(templateRepository, never()).save(any());
        verify(loanRepository, never()).lockApplicant(any());
    }

    @Test
    @DisplayName("a version that was never published is a 404 naming it")
    void missingVersion() {
        when(templateRepository.findByInstrumentTypeAndVersion(InstrumentType.LOAN_AGREEMENT, 9))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.version(InstrumentType.LOAN_AGREEMENT, 9))
                .isInstanceOf(NotFoundException.class).hasMessage("LOAN_AGREEMENT has no version 9");
    }

    @Test
    @DisplayName("what is in force lists only instruments that have been published")
    void inForce() {
        InstrumentTemplate agreement = InstrumentTemplate.builder().instrumentType(InstrumentType.LOAN_AGREEMENT)
                .version(3).title("SSB Loan Agreement").body("x").publishedBy("admin")
                .publishedAt(LocalDateTime.of(2026, 9, 28, 12, 5)).build();
        when(templateRepository.findFirstByInstrumentTypeOrderByVersionDesc(InstrumentType.LOAN_AGREEMENT))
                .thenReturn(Optional.of(agreement));
        when(templateRepository.findFirstByInstrumentTypeOrderByVersionDesc(InstrumentType.SSB_DEDUCTION_AUTHORITY))
                .thenReturn(Optional.empty());

        assertThat(service.current()).extracting(InstrumentTemplateResponse::instrumentType, InstrumentTemplateResponse::version)
                .containsExactly(tuple(InstrumentType.LOAN_AGREEMENT, 3));
    }
}
