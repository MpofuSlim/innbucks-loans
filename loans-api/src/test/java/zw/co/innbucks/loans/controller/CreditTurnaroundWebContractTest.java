package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
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
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.CreditWorkbenchResponse;
import zw.co.innbucks.loans.core.loan.CreditWorkbenchService;
import zw.co.innbucks.loans.core.loan.LoanResponse;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundReportResponse;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaroundService;
import zw.co.innbucks.loans.core.turnaround.ServiceLevelResponse;
import zw.co.innbucks.loans.core.turnaround.ServiceLevelService;
import zw.co.innbucks.loans.core.turnaround.ServiceLevelStage;
import zw.co.innbucks.loans.core.turnaround.UpdateServiceLevelRequest;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The credit workbench and turnaround endpoints (FR-SSB-015): Credit reads the workbench and the report, Finance may
 * also read the service levels, and only SUPER_ADMIN changes one. Runs behind production's {@code @PreAuthorize}
 * interceptor; no database, no Spring context.
 */
class CreditTurnaroundWebContractTest {

    private CreditWorkbenchService workbenchService;
    private ServiceLevelService serviceLevelService;
    private CreditTurnaroundService turnaroundService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        workbenchService = mock(CreditWorkbenchService.class);
        serviceLevelService = mock(ServiceLevelService.class);
        turnaroundService = mock(CreditTurnaroundService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(
                        secured(new CreditWorkbenchController(workbenchService)),
                        secured(new CreditTurnaroundController(serviceLevelService, turnaroundService)))
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

    private static ServiceLevelResponse level(int target, int escalation) {
        return new ServiceLevelResponse(ServiceLevelStage.CREDIT_DECISION, target, escalation, "admin",
                LocalDateTime.of(2026, 10, 2, 6, 50, 31));
    }

    @Test
    @DisplayName("only Credit and administrators open the workbench: an agent or Finance is refused (403)")
    void workbenchIsForCredit() throws Exception {
        LoanResponse loan = new LoanResponse();
        loan.setId(42L);
        when(workbenchService.workbench(42L)).thenReturn(new CreditWorkbenchResponse(loan, null,
                new CreditWorkbenchResponse.Exposure(0, BigDecimal.ZERO, BigDecimal.ZERO, List.of()), List.of(),
                List.of(), List.of()));

        for (String role : List.of("AGENTS", "FINANCE")) {
            mvc.perform(get("/lending/v1/loans/42/credit-workbench").with(as("someone", role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(workbenchService);

        for (String role : List.of("CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/loans/42/credit-workbench").with(as("cmanager", role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.loan.id").value(42))
                    .andExpect(jsonPath("$.data.exposure.openLoans").value(0))
                    .andExpect(jsonPath("$.data.flags").isEmpty());
        }
    }

    @Test
    @DisplayName("an unknown loan's workbench is a 404")
    void unknownLoanIsNotFound() throws Exception {
        when(workbenchService.workbench(99L)).thenThrow(new NotFoundException("Loan 99 not found"));

        mvc.perform(get("/lending/v1/loans/99/credit-workbench").with(as("cmanager", "CREDIT_MANAGER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Loan 99 not found"));
    }

    @Test
    @DisplayName("Credit, Finance and administrators read the service levels; an agent cannot")
    void serviceLevelsAreReadByStaff() throws Exception {
        when(serviceLevelService.list()).thenReturn(List.of(level(24, 48)));

        mvc.perform(get("/lending/v1/service-levels").with(as("tmoyo", "AGENTS")))
                .andExpect(status().isForbidden());
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/service-levels").with(as("someone", role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].stage").value("CREDIT_DECISION"))
                    .andExpect(jsonPath("$.data[0].targetHours").value(24));
        }
    }

    @Test
    @DisplayName("only SUPER_ADMIN changes a service level; Credit cannot set its own target")
    void onlyAdministratorsChangeAServiceLevel() throws Exception {
        when(serviceLevelService.update(eq(ServiceLevelStage.CREDIT_DECISION), any())).thenReturn(level(8, 16));

        mvc.perform(put("/lending/v1/service-levels/CREDIT_DECISION").with(as("cmanager", "CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetHours\": 8, \"escalationHours\": 16}"))
                .andExpect(status().isForbidden());
        verify(serviceLevelService, never()).update(any(), any());

        mvc.perform(put("/lending/v1/service-levels/CREDIT_DECISION").with(as("admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetHours\": 8, \"escalationHours\": 16}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Service level updated; it applies to loans already waiting as well"))
                .andExpect(jsonPath("$.data.targetHours").value(8));
        verify(serviceLevelService).update(ServiceLevelStage.CREDIT_DECISION, new UpdateServiceLevelRequest(8, 16));
    }

    @Test
    @DisplayName("a service level out of range, an escalation before the target, or an unknown stage is a 400")
    void badServiceLevelsAreRefused() throws Exception {
        mvc.perform(put("/lending/v1/service-levels/CREDIT_DECISION").with(as("admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetHours\": 0, \"escalationHours\": 16}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.targetHours").value("Target hours must be at least 1"));

        when(serviceLevelService.update(eq(ServiceLevelStage.CREDIT_DECISION), any()))
                .thenThrow(new IllegalArgumentException("Escalation hours cannot be fewer than the target hours"));
        mvc.perform(put("/lending/v1/service-levels/CREDIT_DECISION").with(as("admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetHours\": 24, \"escalationHours\": 12}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Escalation hours cannot be fewer than the target hours"));

        mvc.perform(put("/lending/v1/service-levels/BOOKING").with(as("admin", "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetHours\": 8, \"escalationHours\": 16}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("Credit and administrators read the turnaround report; Finance and agents cannot")
    void reportIsForCredit() throws Exception {
        when(turnaroundService.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(new CreditTurnaroundReportResponse(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 24,
                        48, 4, 3, new BigDecimal("75.0"), new BigDecimal("10.7"), new BigDecimal("5.8"),
                        new BigDecimal("30.0"), 1, List.of(), 3, 1, 0));

        for (String role : List.of("AGENTS", "FINANCE")) {
            mvc.perform(get("/lending/v1/reports/credit-turnaround").with(as("someone", role)))
                    .andExpect(status().isForbidden());
        }
        for (String role : List.of("CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/reports/credit-turnaround").with(as("someone", role))
                            .param("fromDate", "2026-09-01").param("toDate", "2026-09-30"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.adherencePercent").value(75.0))
                    .andExpect(jsonPath("$.data.unmeasured").value(1));
        }
    }

    @Test
    @DisplayName("an inverted report period is a 400")
    void invertedPeriod() throws Exception {
        when(turnaroundService.report(any(), any())).thenThrow(new ValidationException("fromDate must not be after toDate"));

        mvc.perform(get("/lending/v1/reports/credit-turnaround").with(as("cmanager", "CREDIT_MANAGER"))
                        .param("fromDate", "2026-09-30").param("toDate", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("fromDate must not be after toDate"));
    }
}
