package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotification;
import zw.co.innbucks.loans.core.notice.LoanNotificationRepository;
import zw.co.innbucks.loans.core.notice.LoanNotificationSender;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.user.FindUserServiceImpl;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the applicant was told (FR-SSB-016), read in the caller's loan scope: an originator sees their own loans'
 * notices, Credit sees any, and a loan outside the scope reads exactly like one that does not exist. No database,
 * no Spring context: the real service over mocked repositories.
 */
class LoanNotificationWebContractTest {

    private LoanRepository loanRepository;
    private LoanNotificationRepository notificationRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        notificationRepository = mock(LoanNotificationRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        User agent = new User();
        agent.setId(7L);
        agent.setUsername("tmoyo");
        agent.setMerchant(Merchant.builder().merchantCode("harare-motors").build());
        when(userRepository.findByUsername("tmoyo")).thenReturn(Optional.of(agent));

        LoanNotificationService service = new LoanNotificationService(mock(LoanNotificationSender.class),
                notificationRepository, loanRepository);
        mvc = MockMvcBuilders.standaloneSetup(new LoanNotificationController(service,
                        new LoanReadScopeResolver(new FindUserServiceImpl(userRepository))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static RequestPostProcessor as(String username, String role) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        JwtAuthenticationToken authentication =
                (JwtAuthenticationToken) new RolesJwtAuthenticationConverter().convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return request -> {
            request.setUserPrincipal(authentication);
            return request;
        };
    }

    private static LoanNotification notification(long id, LoanNotice notice, boolean sent, String failureReason) {
        return LoanNotification.builder().id(id).loanId(43L).notice(notice).channel(LoanNotification.SMS)
                .recipient("263772345678").message("Your loan application with ref # 000000043")
                .gatewayReference("LOANS-SMS-" + id).sent(sent).failureReason(failureReason)
                .attemptedAt(LocalDateTime.of(2026, 9, 30, 7, 31, 16)).build();
    }

    @Test
    @DisplayName("an originator reads their own loan's notices, oldest first, each with the stage it announced")
    @SuppressWarnings("unchecked")
    void anOriginatorReadsTheirOwnLoan() throws Exception {
        when(loanRepository.exists(any(Specification.class))).thenReturn(true);
        when(notificationRepository.findByLoanIdOrderByIdAsc(43L)).thenReturn(List.of(
                notification(1L, LoanNotice.RECEIVED, true, null),
                notification(2L, LoanNotice.SSB_CONFIRMED, false, "InnBucks gateway rejected SMS: HTTP 503")));

        mvc.perform(get("/lending/v1/loans/43/notifications").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].notice").value("RECEIVED"))
                .andExpect(jsonPath("$.data[0].stage").value("RECEIVED"))
                .andExpect(jsonPath("$.data[0].channel").value("SMS"))
                .andExpect(jsonPath("$.data[0].sent").value(true))
                .andExpect(jsonPath("$.data[0].failureReason").doesNotExist())
                .andExpect(jsonPath("$.data[1].notice").value("SSB_CONFIRMED"))
                .andExpect(jsonPath("$.data[1].stage").value("WITH_CREDIT"))
                .andExpect(jsonPath("$.data[1].sent").value(false))
                .andExpect(jsonPath("$.data[1].failureReason").value("InnBucks gateway rejected SMS: HTTP 503"))
                .andExpect(jsonPath("$.data[1].gatewayReference").value("LOANS-SMS-2"));
        verify(loanRepository, never()).existsById(any());
    }

    @Test
    @DisplayName("a loan outside the originator's scope is a 404, and nothing about it is read")
    @SuppressWarnings("unchecked")
    void anotherOriginatorsLoanIsNotFound() throws Exception {
        when(loanRepository.exists(any(Specification.class))).thenReturn(false);

        mvc.perform(get("/lending/v1/loans/43/notifications").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Loan 43 not found"));
        verify(notificationRepository, never()).findByLoanIdOrderByIdAsc(any());
    }

    @Test
    @DisplayName("Credit reads any loan's notices; a loan nothing was sent about is an empty list")
    void creditReadsAnyLoan() throws Exception {
        when(loanRepository.existsById(43L)).thenReturn(true);
        when(notificationRepository.findByLoanIdOrderByIdAsc(43L)).thenReturn(List.of());

        mvc.perform(get("/lending/v1/loans/43/notifications").with(as("cmanager", "CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(loanRepository).existsById(43L);
    }
}
