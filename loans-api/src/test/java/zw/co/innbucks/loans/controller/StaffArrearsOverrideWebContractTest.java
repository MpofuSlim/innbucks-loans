package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.staff.loan.StaffArrearsOverrideResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffArrearsOverrideService;
import zw.co.innbucks.loans.core.staff.offer.StaffArrearsOverrideStatus;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;
import zw.co.innbucks.loans.web.StaffArrearsOverrideApiExamples;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The arrears override endpoints (FR-SGL-014): Credit and SUPER_ADMIN propose, decide, withdraw and revoke; staff read;
 * Human Capital and Finance cannot lift the arrears rule; every refusal reaches the client in the envelope.
 */
class StaffArrearsOverrideWebContractTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 6, 7, 30, 12);
    private static final LocalDate LAST_DAY = LocalDate.of(2026, 10, 31);
    private static final String REASON = "Repayment plan for the written-off balance agreed with Finance; payroll"
            + " deduction confirmed";
    private static final StaffArrearsOverrideResponse PENDING = new StaffArrearsOverrideResponse(1L, "E1060",
            "Tatenda Mhlanga", REASON, LAST_DAY, StaffArrearsOverrideStatus.PENDING, "credit1", AT, null, null, null,
            null, null, null, null, null, null, null, null, null);
    private static final StaffArrearsOverrideResponse APPROVED = new StaffArrearsOverrideResponse(1L, "E1060",
            "Tatenda Mhlanga", REASON, LAST_DAY, StaffArrearsOverrideStatus.APPROVED, "credit1", AT, "credit2",
            AT.plusMinutes(45), "Plan confirmed with Finance", null, null, null, null, null, null, null, true, null);
    private static final StaffArrearsOverrideResponse USED = new StaffArrearsOverrideResponse(1L, "E1060",
            "Tatenda Mhlanga", REASON, LAST_DAY, StaffArrearsOverrideStatus.USED, "credit1", AT, "credit2",
            AT.plusMinutes(45), "Plan confirmed with Finance", null, null, null, null, null, "SGL-2026-000158",
            AT.plusDays(1), null, null);

    private StaffArrearsOverrideService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffArrearsOverrideService.class);
        ProxyFactory secured = new ProxyFactory(new StaffArrearsOverrideController(service));
        secured.setProxyTargetClass(true);
        secured.addAdvisor(preAuthorize());
        mvc = MockMvcBuilders.standaloneSetup(secured.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static RequestPostProcessor as(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("preferred_username", "someone")
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

    @Test
    @DisplayName("only Credit and SUPER_ADMIN propose (201); Human Capital, finance and agents cannot; fields are"
            + " validated, and the service's refusals come through")
    void propose() throws Exception {
        when(service.propose(any())).thenReturn(PENDING);

        for (String role : List.of("HUMAN_CAPITAL", "FINANCE", "AGENTS", "MERCHANT_TILL")) {
            mvc.perform(post("/lending/v1/staff-arrears-overrides").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.PROPOSAL))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        for (String role : List.of("CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(post("/lending/v1/staff-arrears-overrides").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.PROPOSAL))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("CREATED"))
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.validUntil").value("2026-10-31"))
                    .andExpect(jsonPath("$.data.inForce").doesNotExist())
                    .andExpect(jsonPath("$.data.staffLoanReference").doesNotExist());
        }
        mvc.perform(post("/lending/v1/staff-arrears-overrides").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNumber\": \"E1060\", \"reason\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.validUntil").value("Valid until is required"))
                .andExpect(jsonPath("$.data.reason").value("Reason is required"));

        when(service.propose(any())).thenThrow(new ConflictException("Employee E1012 owes no written-off Staff"
                + " Grocery Loan, so there is nothing to override. An overdue loan cannot be overridden: it must be"
                + " repaid first"));
        mvc.perform(post("/lending/v1/staff-arrears-overrides").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.PROPOSAL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        doThrow(new ValidationException("Valid until must be at most 90 days ahead, on or before 2027-01-04"))
                .when(service).propose(any());
        mvc.perform(post("/lending/v1/staff-arrears-overrides").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.PROPOSAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Valid until must be at most 90 days ahead, on or before"
                        + " 2027-01-04"));
    }

    @Test
    @DisplayName("deciding, withdrawing and revoking reach the service with their own messages and refusals")
    void decisions() throws Exception {
        when(service.decide(eq(1L), any())).thenReturn(APPROVED);
        when(service.decide(eq(2L), any())).thenThrow(new AccessDeniedException("credit1 proposed arrears override 2"
                + " and cannot also approve or reject it; another credit manager or SUPER_ADMIN must"));
        when(service.withdraw(3L)).thenThrow(new ConflictException("Arrears override 3 is already approved"));
        when(service.revoke(eq(1L), any())).thenReturn(APPROVED);
        when(service.revoke(eq(4L), any())).thenThrow(new ConflictException("Arrears override 4 is used, so there is"
                + " nothing to revoke"));

        mvc.perform(post("/lending/v1/staff-arrears-overrides/1/decision").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.APPROVAL))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/staff-arrears-overrides/1/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.APPROVAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Arrears override approved; the employee may take one Staff"
                        + " Grocery Loan under it until 2026-10-31"))
                .andExpect(jsonPath("$.data.inForce").value(true));
        mvc.perform(post("/lending/v1/staff-arrears-overrides/1/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"REJECTED\","
                                + " \"comment\": \"No plan yet\"}"))
                .andExpect(jsonPath("$.message").value("Arrears override rejected"));
        mvc.perform(post("/lending/v1/staff-arrears-overrides/2/decision").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.APPROVAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("credit1 proposed arrears override 2 and cannot also approve"
                        + " or reject it; another credit manager or SUPER_ADMIN must"));
        mvc.perform(post("/lending/v1/staff-arrears-overrides/1/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.decision").value("Decision is required (APPROVED or REJECTED)"));
        mvc.perform(delete("/lending/v1/staff-arrears-overrides/3").with(as("FINANCE")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/lending/v1/staff-arrears-overrides/3").with(as("CREDIT_MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Arrears override 3 is already approved"));
        mvc.perform(post("/lending/v1/staff-arrears-overrides/1/revocation").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.REVOCATION))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/staff-arrears-overrides/1/revocation").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reason").value("Reason is required"));
        mvc.perform(post("/lending/v1/staff-arrears-overrides/1/revocation").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.REVOCATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Arrears override revoked; the written-off balance stops new"
                        + " loans again"));
        mvc.perform(post("/lending/v1/staff-arrears-overrides/4/revocation").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffArrearsOverrideApiExamples.REVOCATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Arrears override 4 is used, so there is nothing to revoke"));
    }

    @Test
    @DisplayName("staff read overrides; agents and tills are refused; a used one names its loan; filters reach the"
            + " service")
    void reads() throws Exception {
        when(service.overrides(any(), any(), any())).thenReturn(new PageImpl<>(List.of(USED),
                PageRequest.of(0, 20), 1));

        for (String role : List.of("AGENTS", "MERCHANT_TILL")) {
            mvc.perform(get("/lending/v1/staff-arrears-overrides").with(as(role))).andExpect(status().isForbidden());
        }
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "HUMAN_CAPITAL", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/staff-arrears-overrides").with(as(role))).andExpect(status().isOk());
        }
        mvc.perform(get("/lending/v1/staff-arrears-overrides").param("status", "USED")
                        .param("employeeNumber", "e1060").param("page", "2").with(as("FINANCE")))
                .andExpect(jsonPath("$.data.items[0].status").value("USED"))
                .andExpect(jsonPath("$.data.items[0].staffLoanReference").value("SGL-2026-000158"))
                .andExpect(jsonPath("$.data.items[0].inForce").doesNotExist());
        verify(service).overrides(eq(StaffArrearsOverrideStatus.USED), eq("e1060"), eq(PageRequest.of(2, 20)));
        mvc.perform(get("/lending/v1/staff-arrears-overrides").param("status", "LIVE").with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'status'"));
    }
}
