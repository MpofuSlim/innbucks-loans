package zw.co.reikan.loans.core.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.exception.BusinessException;
import zw.co.reikan.loans.core.exception.LoanApprovalException;
import zw.co.reikan.loans.core.exception.NotFoundException;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The credit-manager refusals are TYPED so they reach the client as a 400/404
 * with their message. They were bare RuntimeExceptions — a 500 that read like a
 * server fault — and "already approved" was also the text for a REJECTED loan.
 */
class InternalApprovalServiceImplTest {

    private LoanRepository loanRepository;
    private InternalApprovalServiceImpl service;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        service = new InternalApprovalServiceImpl(loanRepository, mock(AuthService.class),
                mock(LoanMapper.class), mock(NotificationService.class));
    }

    private static InternalApprovalRequest decide(InternalApprovalStatus status) {
        InternalApprovalRequest request = new InternalApprovalRequest();
        request.setStatus(status);
        return request;
    }

    private void given(LoanApprovalStatus payroll, InternalApprovalStatus internal) {
        Loan loan = Loan.builder().loanApprovalStatus(payroll).internalApprovalStatus(internal).build();
        loan.setId(42L);
        when(loanRepository.findById(42L)).thenReturn(Optional.of(loan));
    }

    @Test
    @DisplayName("an unknown loan is a NotFoundException (404), not NoSuchElementException (500)")
    void unknownLoanIsNotFound() {
        when(loanRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 7 not found");
    }

    @Test
    @DisplayName("PENDING is not a decision")
    void pendingIsNotADecision() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.PENDING), 42L))
                .isInstanceOf(LoanApprovalException.class)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid status: a decision must be APPROVED or REJECTED");
    }

    @Test
    @DisplayName("a decided loan names the decision it already holds — approved OR rejected")
    void alreadyDecidedNamesTheDecision() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.REJECTED);

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan has already been rejected");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a loan not yet payroll-approved cannot be signed off")
    void notPayrollApprovedIsRefused() {
        given(LoanApprovalStatus.NEW, InternalApprovalStatus.PENDING);

        assertThatThrownBy(() -> service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan with status NEW cannot be approved");
    }

    @Test
    @DisplayName("a valid decision is saved")
    void validDecisionIsSaved() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        InternalApprovalResponse response = service.approveLoan(decide(InternalApprovalStatus.APPROVED), 42L);

        assertThat(response.getMessage()).isEqualTo("Approved successfully");
        verify(loanRepository).save(any());
    }
}
