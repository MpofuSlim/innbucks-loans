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
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideResponse;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideService;
import zw.co.innbucks.loans.core.staff.offer.StaffLimitOverrideStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
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
 * The limit override endpoints (FR-SGL-011): Credit and SUPER_ADMIN propose, decide, withdraw and revoke; staff read;
 * Human Capital and Finance cannot authorise a limit; every refusal reaches the client in the envelope.
 */
class StaffLimitOverrideWebContractTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 6, 7, 12, 30);
    private static final StaffLimitOverrideResponse PENDING = new StaffLimitOverrideResponse(1L, "E1012",
            "Chipo Banda", "C4", new BigDecimal("150.00"), "Existing salary advance outstanding until December",
            StaffLimitOverrideStatus.PENDING, "credit1", AT, null, null, null, null, null, null, null, null, null,
            null);
    private static final StaffLimitOverrideResponse APPROVED = new StaffLimitOverrideResponse(1L, "E1012",
            "Chipo Banda", "C4", new BigDecimal("150.00"), "Existing salary advance outstanding until December",
            StaffLimitOverrideStatus.APPROVED, "credit1", AT, "credit2", AT.plusMinutes(49), "Confirmed with Payroll",
            null, null, null, null, null, true, null);

    private StaffLimitOverrideService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffLimitOverrideService.class);
        ProxyFactory secured = new ProxyFactory(new StaffLimitOverrideController(service));
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
            + " validated")
    void propose() throws Exception {
        when(service.propose(any())).thenReturn(PENDING);

        for (String role : List.of("HUMAN_CAPITAL", "FINANCE", "AGENTS")) {
            mvc.perform(post("/lending/v1/staff-limit-overrides").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LIMIT_OVERRIDE_PROPOSAL))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        for (String role : List.of("CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(post("/lending/v1/staff-limit-overrides").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LIMIT_OVERRIDE_PROPOSAL))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("CREATED"))
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.inForce").doesNotExist());
        }
        mvc.perform(post("/lending/v1/staff-limit-overrides").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeNumber\": \"E1012\", \"amount\": -1, \"reason\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.amount").value("Amount cannot be negative"))
                .andExpect(jsonPath("$.data.reason").value("Reason is required"));
    }

    @Test
    @DisplayName("deciding, withdrawing and revoking reach the service with their own messages and refusals")
    void decisions() throws Exception {
        when(service.decide(eq(1L), any())).thenReturn(APPROVED);
        when(service.decide(eq(2L), any())).thenThrow(new AccessDeniedException("credit1 proposed limit override 2"
                + " and cannot also approve or reject it; another credit manager or SUPER_ADMIN must"));
        when(service.withdraw(3L)).thenThrow(new ConflictException("Limit override 3 is already approved"));
        when(service.revoke(eq(1L), any())).thenReturn(APPROVED);

        mvc.perform(post("/lending/v1/staff-limit-overrides/1/decision").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LIMIT_OVERRIDE_APPROVAL))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/staff-limit-overrides/1/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LIMIT_OVERRIDE_APPROVAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Limit override approved; it sets the employee's offer from"
                        + " the next weekly run"))
                .andExpect(jsonPath("$.data.inForce").value(true));
        mvc.perform(post("/lending/v1/staff-limit-overrides/1/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"REJECTED\","
                                + " \"comment\": \"No\"}"))
                .andExpect(jsonPath("$.message").value("Limit override rejected"));
        mvc.perform(post("/lending/v1/staff-limit-overrides/2/decision").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LIMIT_OVERRIDE_APPROVAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("credit1 proposed limit override 2 and cannot also approve"
                        + " or reject it; another credit manager or SUPER_ADMIN must"));
        mvc.perform(post("/lending/v1/staff-limit-overrides/1/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.decision").value("Decision is required (APPROVED or REJECTED)"));
        mvc.perform(delete("/lending/v1/staff-limit-overrides/3").with(as("CREDIT_MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Limit override 3 is already approved"));
        mvc.perform(post("/lending/v1/staff-limit-overrides/1/revocation").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LIMIT_OVERRIDE_REVOCATION))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/staff-limit-overrides/1/revocation").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reason").value("Reason is required"));
        mvc.perform(post("/lending/v1/staff-limit-overrides/1/revocation").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LIMIT_OVERRIDE_REVOCATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Limit override revoked; the employee's grade limit applies"
                        + " from the next weekly run"));
    }

    @Test
    @DisplayName("staff read overrides; agents are refused; filters and paging reach the service")
    void reads() throws Exception {
        when(service.overrides(any(), any(), any())).thenReturn(new PageImpl<>(List.of(APPROVED),
                PageRequest.of(0, 20), 1));

        mvc.perform(get("/lending/v1/staff-limit-overrides").with(as("AGENTS"))).andExpect(status().isForbidden());
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "HUMAN_CAPITAL", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/staff-limit-overrides").with(as(role))).andExpect(status().isOk());
        }
        mvc.perform(get("/lending/v1/staff-limit-overrides").param("status", "PENDING")
                        .param("employeeNumber", "e1012").param("page", "2").with(as("FINANCE")))
                .andExpect(jsonPath("$.data.items[0].inForce").value(true))
                .andExpect(jsonPath("$.data.items[0].notInForceReason").doesNotExist());
        verify(service).overrides(eq(StaffLimitOverrideStatus.PENDING), eq("e1012"), eq(PageRequest.of(2, 20)));
        mvc.perform(get("/lending/v1/staff-limit-overrides").param("status", "LIVE").with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'status'"));
    }
}
