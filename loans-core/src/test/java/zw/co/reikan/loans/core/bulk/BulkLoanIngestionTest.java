package zw.co.reikan.loans.core.bulk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.exception.ConflictException;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanPublicReferenceService;
import zw.co.reikan.loans.core.loan.LoanRequest;
import zw.co.reikan.loans.core.loan.LoanService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Each bulk row goes through the single-application flow, which books the loan under whoever is
 * signed in. The rows run on worker threads, and the security context is per thread, so without
 * handing it over every row was booked as SYSTEM_USER, a user that no longer exists. A refused
 * row, the applicant already having a loan in flight, must count as a failure, not an applied loan.
 */
class BulkLoanIngestionTest {

    private LoanService loanService;
    private LoanPublicReferenceService publicReferenceService;
    private AuditService auditService;
    private BulkLoanIngestionService ingestion;

    @BeforeEach
    void setUp() {
        loanService = mock(LoanService.class);
        publicReferenceService = mock(LoanPublicReferenceService.class);
        auditService = mock(AuditService.class);
        BulkIngestionRunRepository runRepository = mock(BulkIngestionRunRepository.class);
        when(runRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BulkIngestionProperties properties = new BulkIngestionProperties();
        properties.setChunkSize(2);
        BulkLoanItemProcessor processor = new BulkLoanItemProcessor(loanService, publicReferenceService);
        ingestion = new BulkLoanIngestionService(properties, processor, runRepository, auditService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static List<LoanRequest> applications(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> LoanRequest.builder().ecnumber("123456" + i + "A").build())
                .toList();
    }

    private static LoanResponse applied(String reference) {
        return LoanResponse.builder().loanApprovalStatus(LoanApprovalStatus.NEW).internalReference(reference)
                .message("Loan Sent For Approval").build();
    }

    private static LoanResponse refusedAsInFlight() {
        return LoanResponse.builder().loanApprovalStatus(LoanApprovalStatus.REJECTED)
                .message("You have a pending loan application.").build();
    }

    @Test
    @DisplayName("every row, in every chunk, is booked as the uploader")
    void rowsRunAsTheUploader() {
        Authentication uploader = new TestingAuthenticationToken("agent.moyo", "n/a", "ROLE_AGENTS");
        SecurityContextHolder.getContext().setAuthentication(uploader);
        List<String> bookedAs = new CopyOnWriteArrayList<>();
        when(loanService.requestLoan(any())).thenAnswer(i -> {
            Authentication current = SecurityContextHolder.getContext().getAuthentication();
            bookedAs.add(current == null ? "nobody" : current.getName());
            return applied("0000000" + bookedAs.size());
        });

        BulkLoanIngestionService.BulkResult result = ingestion.ingest(applications(5), "agent.moyo", null);

        assertThat(result.succeeded()).isEqualTo(5);
        assertThat(bookedAs).hasSize(5).containsOnly("agent.moyo");
        // The caller's own context is left exactly as it was.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(uploader);
    }

    @Test
    @DisplayName("a row refused because a loan is in flight is a failure with the refusal's reason, and audited")
    void refusedRowIsAFailure() {
        when(loanService.requestLoan(any())).thenReturn(applied("00000001"), refusedAsInFlight());
        when(loanService.findByReference("00000001")).thenReturn(Optional.of(Loan.builder().build()));
        when(publicReferenceService.next()).thenReturn("LN-2026-00001");

        BulkLoanIngestionService.BulkResult result = ingestion.ingest(applications(2), "agent.moyo", null);

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        BulkLoanItemOutcome refused = result.outcomes().get(1);
        assertThat(refused.success()).isFalse();
        assertThat(refused.error()).isEqualTo("You have a pending loan application.");
        verify(auditService, atLeastOnce()).record(argThat((AuditLog.AuditLogBuilder b) ->
                "BULK_ITEM_REJECTED".equals(b.build().getEventType())));
    }

    @Test
    @DisplayName("the processor refuses before drawing a public reference for a loan that was never created")
    void processorDrawsNoReferenceForARefusal() {
        BulkLoanItemProcessor processor = new BulkLoanItemProcessor(loanService, publicReferenceService);
        when(loanService.requestLoan(any())).thenReturn(refusedAsInFlight());

        assertThatThrownBy(() -> processor.process(0, applications(1).getFirst()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("You have a pending loan application.");
        verify(loanService, never()).findByReference(anyString());
        verifyNoInteractions(publicReferenceService);
    }
}
