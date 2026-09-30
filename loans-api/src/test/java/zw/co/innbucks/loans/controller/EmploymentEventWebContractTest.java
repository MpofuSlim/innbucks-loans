package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.employment.ApplicationTreatment;
import zw.co.innbucks.loans.core.employment.EmploymentEventResponse;
import zw.co.innbucks.loans.core.employment.EmploymentEventService;
import zw.co.innbucks.loans.core.employment.EmploymentEventTreatmentResponse;
import zw.co.innbucks.loans.core.employment.EmploymentEventTreatmentService;
import zw.co.innbucks.loans.core.employment.EmploymentEventType;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventAction;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventOutcome;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventResponse;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventStatus;
import zw.co.innbucks.loans.core.employment.LoanTreatment;
import zw.co.innbucks.loans.core.employment.RecordEmploymentEventRequest;
import zw.co.innbucks.loans.core.employment.UpdateEmploymentEventTreatmentRequest;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.user.FindUserServiceImpl;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The employment event endpoints (FR-SSB-024): Credit records events and resolves the queue, Finance reads them, only
 * SUPER_ADMIN changes a treatment, and a loan's events are read in the caller's loan scope. Runs behind production's
 * {@code @PreAuthorize} interceptor; no database, no Spring context.
 */
class EmploymentEventWebContractTest {

    private static final String SUSPENSION = """
            {"ecNumber": "1234567A", "eventType": "SUSPENSION", "effectiveDate": "2026-10-01", "endDate": "2026-12-31"}""";

    private EmploymentEventService eventService;
    private EmploymentEventTreatmentService treatmentService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        eventService = mock(EmploymentEventService.class);
        treatmentService = mock(EmploymentEventTreatmentService.class);
        UserRepository userRepository = mock(UserRepository.class);
        User agent = new User();
        agent.setId(7L);
        agent.setUsername("tmoyo");
        agent.setMerchant(Merchant.builder().merchantCode("harare-motors").build());
        when(userRepository.findByUsername("tmoyo")).thenReturn(Optional.of(agent));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(
                        secured(new EmploymentEventController(eventService,
                                new LoanReadScopeResolver(new FindUserServiceImpl(userRepository)))),
                        secured(new EmploymentEventTreatmentController(treatmentService)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private static Object secured(Object controller) {
        ProxyFactory secured = new ProxyFactory(controller);
        secured.setProxyTargetClass(true);
        secured.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return secured.getProxy();
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

    private static EmploymentEventResponse recorded() {
        return new EmploymentEventResponse(5L, "1234567A", EmploymentEventType.SUSPENSION, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 12, 31), null, null, null, null, "cmanager", null, List.of(held()));
    }

    private static LoanEmploymentEventResponse held() {
        return new LoanEmploymentEventResponse(11L, 5L, EmploymentEventType.SUSPENSION, LocalDate.of(2026, 10, 1),
                42L, "000000042", "Rudo Chikwanha", null, LoanEmploymentEventAction.HOLD,
                LoanEmploymentEventStatus.OPEN, null, null, null, null, null);
    }

    @Test
    @DisplayName("only Credit records an event: an agent or Finance is refused (403) and nothing is recorded")
    void onlyCreditRecords() throws Exception {
        when(eventService.record(any())).thenReturn(recorded());

        for (String role : List.of("AGENTS", "FINANCE")) {
            mvc.perform(post("/lending/v1/employment-events").with(as("someone", role))
                            .contentType(MediaType.APPLICATION_JSON).content(SUSPENSION))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(eventService);

        mvc.perform(post("/lending/v1/employment-events").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(SUSPENSION))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("Employment event recorded"))
                .andExpect(jsonPath("$.data.loans[0].action").value("HOLD"))
                .andExpect(jsonPath("$.data.loans[0].status").value("OPEN"));
        verify(eventService).record(RecordEmploymentEventRequest.builder().ecNumber("1234567A")
                .eventType(EmploymentEventType.SUSPENSION).effectiveDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 12, 31)).build());
    }

    @Test
    @DisplayName("recording needs an EC number, a type and an effective date, all reported in one 400")
    void recordingNeedsItsFields() throws Exception {
        mvc.perform(post("/lending/v1/employment-events").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.ecNumber").value("EC number is required"))
                .andExpect(jsonPath("$.data.eventType").value("Event type is required"))
                .andExpect(jsonPath("$.data.effectiveDate").value("Effective date is required"));
        verifyNoInteractions(eventService);
    }

    @Test
    @DisplayName("Finance reads the events and the queue but cannot resolve; Credit resolves, told what happened")
    void readingAndResolving() throws Exception {
        when(eventService.find(any(), any())).thenReturn(new PageImpl<>(List.of(recorded())));
        when(eventService.queue()).thenReturn(List.of(held()));
        when(eventService.resolve(eq(11L), any())).thenReturn(held());

        mvc.perform(get("/lending/v1/employment-events").param("ecNumber", "1234567A").with(as("finance", "FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(5))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        mvc.perform(get("/lending/v1/loan-employment-events").with(as("finance", "FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].loanReference").value("000000042"));
        mvc.perform(get("/lending/v1/loan-employment-events").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isForbidden());
        String release = """
                {"outcome": "RELEASED", "comment": "Suspension lifted on appeal"}""";
        mvc.perform(post("/lending/v1/loan-employment-events/11/resolution").with(as("finance", "FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content(release))
                .andExpect(status().isForbidden());
        verify(eventService, never()).resolve(any(), any());

        mvc.perform(post("/lending/v1/loan-employment-events/11/resolution").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(release))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hold released; the application carries on"));
        mvc.perform(post("/lending/v1/loan-employment-events/11/resolution").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"REVIEWED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.comment").value("Comment is required"));
        verify(eventService).resolve(eq(11L), any());
    }

    @Test
    @DisplayName("only SUPER_ADMIN changes a treatment; a path naming no event type is a 400")
    void onlySuperAdminChangesTreatments() throws Exception {
        String change = """
                {"applicationTreatment": "CONTINUE", "loanTreatment": "REVIEW", "notifyOnDecline": true}""";
        when(treatmentService.update(eq(EmploymentEventType.SECONDMENT), any())).thenReturn(
                new EmploymentEventTreatmentResponse(EmploymentEventType.SECONDMENT, ApplicationTreatment.CONTINUE,
                        LoanTreatment.REVIEW, true, "admin", null));

        mvc.perform(get("/lending/v1/employment-event-treatments").with(as("finance", "FINANCE")))
                .andExpect(status().isOk());
        mvc.perform(put("/lending/v1/employment-event-treatments/SECONDMENT").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isForbidden());
        verify(treatmentService, never()).update(any(), any());

        mvc.perform(put("/lending/v1/employment-event-treatments/SECONDMENT").with(as("admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Treatment updated; it applies to SECONDMENT events recorded from now on"))
                .andExpect(jsonPath("$.data.applicationTreatment").value("CONTINUE"));
        verify(treatmentService).update(EmploymentEventType.SECONDMENT,
                new UpdateEmploymentEventTreatmentRequest(ApplicationTreatment.CONTINUE, LoanTreatment.REVIEW, true));

        mvc.perform(put("/lending/v1/employment-event-treatments/REDUNDANCY").with(as("admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Invalid value for 'eventType'"));
    }

    @Test
    @DisplayName("a loan's employment events are read in the caller's scope: an agent's own, Credit's all")
    void loanEventsCarryTheScope() throws Exception {
        when(eventService.forLoan(any(), any())).thenReturn(List.of(held()));

        mvc.perform(get("/lending/v1/loans/42/employment-events").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("HOLD"));
        verify(eventService).forLoan(42L, LoanReadScope.originator("harare-motors", 7L));

        mvc.perform(get("/lending/v1/loans/42/employment-events").with(as("cmanager", "CREDIT_MANAGER")))
                .andExpect(status().isOk());
        verify(eventService).forLoan(42L, LoanReadScope.platform());
    }

    @Test
    @DisplayName("each outcome is answered in words")
    void outcomeMessages() throws Exception {
        when(eventService.resolve(any(), any())).thenReturn(held());
        for (LoanEmploymentEventOutcome outcome : LoanEmploymentEventOutcome.values()) {
            mvc.perform(post("/lending/v1/loan-employment-events/11/resolution").with(as("cmanager", "CREDIT_MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"outcome\": \"" + outcome + "\", \"comment\": \"Checked\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }
}
