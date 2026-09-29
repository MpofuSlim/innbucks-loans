package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;
import zw.co.innbucks.loans.core.disbursements.BookingFailureKind;
import zw.co.innbucks.loans.core.disbursements.BookingResolutionService;
import zw.co.innbucks.loans.core.disbursements.HeldBookingResponse;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.exception.ConflictException;

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
 * eligible for a recovery payout, so it is SUPER_ADMIN only — the same authority as the payout
 * itself. The controller carries the real {@code @PreAuthorize} interceptor; no Spring context.
 */
class HeldBookingWebContractTest {

    private static final String NOTE = "{\"note\":\"InnBucks ops confirmed no loan under 000000042, INC-7781\"}";

    private BookingResolutionService resolutionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        resolutionService = mock(BookingResolutionService.class);

        HeldBookingController management = new HeldBookingController(resolutionService);
        ProxyFactory proxy = new ProxyFactory(management);
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());


        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(proxy.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler())
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

    private static HeldBookingResponse held() {
        return HeldBookingResponse.builder().loanId(42L).reference("000000042")
                .bookingStatus(LoanAccountStatus.CREATED).disbursementStatus(LoanDisbursementStatus.PENDING)
                .bookingFailureKind(BookingFailureKind.AMBIGUOUS).build();
    }

    @Test
    @DisplayName("GET /lending/v1/held-bookings: finance may read the queue")
    void financeReadsTheQueue() throws Exception {
        signedInAs("FINANCE");
        when(resolutionService.findHeld()).thenReturn(List.of(held()));

        mvc.perform(get("/lending/v1/held-bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reference").value("000000042"))
                .andExpect(jsonPath("$.data[0].bookingFailureKind").value("AMBIGUOUS"));
    }

    @Test
    @DisplayName("GET /lending/v1/held-bookings: an agent is refused")
    void agentCannotReadTheQueue() throws Exception {
        signedInAs("AGENTS");

        mvc.perform(get("/lending/v1/held-bookings")).andExpect(status().isForbidden());
        verifyNoInteractions(resolutionService);
    }

    @Test
    @DisplayName("POST booking/not-booked: a SUPER_ADMIN records it with a note")
    void adminConfirms() throws Exception {
        signedInAs("SUPER_ADMIN");
        HeldBookingResponse failed = held();
        failed.setBookingStatus(LoanAccountStatus.FAILED);
        failed.setDisbursementStatus(LoanDisbursementStatus.FAILED);
        failed.setBookingFailureKind(BookingFailureKind.REFUSED);
        when(resolutionService.confirmNotBooked(eq(42L), anyString())).thenReturn(failed);

        mvc.perform(post("/lending/v1/loans/42/booking/not-booked")
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.bookingFailureKind").value("REFUSED"));
        verify(resolutionService).confirmNotBooked(42L, "InnBucks ops confirmed no loan under 000000042, INC-7781");
    }

    @Test
    @DisplayName("POST booking/not-booked: FINANCE and CREDIT_MANAGER are refused — it opens the loan to a payout")
    void onlyAnAdminMayConfirm() throws Exception {
        for (String role : List.of("FINANCE", "CREDIT_MANAGER")) {
            signedInAs(role);
            mvc.perform(post("/lending/v1/loans/42/booking/not-booked")
                            .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(resolutionService);
    }

    @Test
    @DisplayName("POST booking/not-booked: a blank note is a 400 and nothing is recorded")
    void blankNoteIsRefused() throws Exception {
        signedInAs("SUPER_ADMIN");

        mvc.perform(post("/lending/v1/loans/42/booking/not-booked")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(resolutionService);
    }

    @Test
    @DisplayName("POST booking/not-booked: a loan with no held booking is a 409 naming why")
    void notHeldIsAConflict() throws Exception {
        signedInAs("SUPER_ADMIN");
        when(resolutionService.confirmNotBooked(anyLong(), anyString())).thenThrow(new ConflictException(
                "Loan 42 has no booking awaiting InnBucks (account status FAILED, disbursement status FAILED)"));

        mvc.perform(post("/lending/v1/loans/42/booking/not-booked")
                        .contentType(MediaType.APPLICATION_JSON).content(NOTE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "Loan 42 has no booking awaiting InnBucks (account status FAILED, disbursement status FAILED)"));
    }
}
