package zw.co.reikan.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import zw.co.reikan.loans.advice.RestExceptionHandler;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.disbursements.BookingFailureKind;
import zw.co.reikan.loans.core.disbursements.BookingResolutionService;
import zw.co.reikan.loans.core.disbursements.HeldBookingDto;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.exception.ConflictException;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.InternalApprovalService;
import zw.co.reikan.loans.core.loan.LoanService;
import zw.co.reikan.loans.core.user.FindUserService;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The held-bookings queue and its one way out. Confirming a booking never landed makes the loan
 * eligible for a recovery payout, so it is BULKIT_ADMIN only — the same authority as the payout
 * itself. The controller carries the real {@code @PreAuthorize} interceptor; no Spring context.
 */
class HeldBookingWebContractTest {

    private static final String NOTE = "{\"note\":\"InnBucks ops confirmed no loan under 000000042, INC-7781\"}";

    private BookingResolutionService resolutionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        resolutionService = mock(BookingResolutionService.class);

        LoanManagementController management = new LoanManagementController();
        ReflectionTestUtils.setField(management, "bookingResolutionService", resolutionService);
        ReflectionTestUtils.setField(management, "deductionCancellationService", mock(DeductionCancellationService.class));
        ReflectionTestUtils.setField(management, "internalApprovalService", mock(InternalApprovalService.class));
        ReflectionTestUtils.setField(management, "disbursementService", mock(DisbursementService.class));
        ProxyFactory proxy = new ProxyFactory(management);
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());

        // Registered alongside, to prove the literal /loans/held-bookings beats /loans/{id}.
        BatchesController batches = new BatchesController();
        ReflectionTestUtils.setField(batches, "loanService", mock(LoanService.class));
        ReflectionTestUtils.setField(batches, "findUserService", mock(FindUserService.class));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(proxy.getProxy(), batches)
                .setControllerAdvice(new RestExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("someone", "n/a", "ROLE_" + role));
    }

    private static HeldBookingDto held() {
        return HeldBookingDto.builder().id(42L).reference("000000042")
                .loanAccountStatus(LoanAccountStatus.CREATED).disbursementStatus(LoanDisbursementStatus.PENDING)
                .bookingFailureKind(BookingFailureKind.AMBIGUOUS).build();
    }

    @Test
    @DisplayName("GET /api/loans/held-bookings: finance may read the queue")
    void financeReadsTheQueue() throws Exception {
        signedInAs("FINANCE");
        when(resolutionService.findHeld()).thenReturn(List.of(held()));

        mvc.perform(get("/api/loans/held-bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reference").value("000000042"))
                .andExpect(jsonPath("$[0].bookingFailureKind").value("AMBIGUOUS"));
    }

    @Test
    @DisplayName("GET /api/loans/held-bookings: an agent is refused")
    void agentCannotReadTheQueue() throws Exception {
        signedInAs("AGENTS");

        mvc.perform(get("/api/loans/held-bookings")).andExpect(status().isForbidden());
        verifyNoInteractions(resolutionService);
    }

    @Test
    @DisplayName("POST confirm-not-booked: a BULKIT_ADMIN records it with a note")
    void adminConfirms() throws Exception {
        signedInAs("BULKIT_ADMIN");
        HeldBookingDto failed = held();
        failed.setLoanAccountStatus(LoanAccountStatus.FAILED);
        failed.setDisbursementStatus(LoanDisbursementStatus.FAILED);
        failed.setBookingFailureKind(BookingFailureKind.REFUSED);
        when(resolutionService.confirmNotBooked(eq(42L), anyString())).thenReturn(failed);

        mvc.perform(post("/api/loans/42/booking/confirm-not-booked")
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccountStatus").value("FAILED"))
                .andExpect(jsonPath("$.bookingFailureKind").value("REFUSED"));
        verify(resolutionService).confirmNotBooked(42L, "InnBucks ops confirmed no loan under 000000042, INC-7781");
    }

    @Test
    @DisplayName("POST confirm-not-booked: FINANCE and CREDIT_MANAGER are refused — it opens the loan to a payout")
    void onlyAnAdminMayConfirm() throws Exception {
        for (String role : List.of("FINANCE", "CREDIT_MANAGER")) {
            signedInAs(role);
            mvc.perform(post("/api/loans/42/booking/confirm-not-booked")
                            .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(resolutionService);
    }

    @Test
    @DisplayName("POST confirm-not-booked: a blank note is a 400 and nothing is recorded")
    void blankNoteIsRefused() throws Exception {
        signedInAs("BULKIT_ADMIN");

        mvc.perform(post("/api/loans/42/booking/confirm-not-booked")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(resolutionService);
    }

    @Test
    @DisplayName("POST confirm-not-booked: a loan with no held booking is a 409 naming why")
    void notHeldIsAConflict() throws Exception {
        signedInAs("BULKIT_ADMIN");
        when(resolutionService.confirmNotBooked(anyLong(), anyString())).thenThrow(new ConflictException(
                "Loan 42 has no booking awaiting InnBucks (account status FAILED, disbursement status FAILED)"));

        mvc.perform(post("/api/loans/42/booking/confirm-not-booked")
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "Loan 42 has no booking awaiting InnBucks (account status FAILED, disbursement status FAILED)"));
    }
}
