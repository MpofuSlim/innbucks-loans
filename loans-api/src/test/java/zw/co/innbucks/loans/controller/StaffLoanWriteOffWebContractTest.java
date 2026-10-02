package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffKind;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanWriteOffStatus;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;
import zw.co.innbucks.loans.web.StaffLoanWriteOffApiExamples;

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
 * The write-off endpoints (FR-GEN-011): Credit, Finance and SUPER_ADMIN propose; only Finance and SUPER_ADMIN decide;
 * staff read; every refusal reaches the client in the envelope.
 */
class StaffLoanWriteOffWebContractTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 12, 15, 8, 20, 31);
    private static final String REASON = "Resigned in October; terminal benefits did not cover the balance and"
            + " recovery has failed";
    private static final StaffLoanWriteOffResponse PENDING = response(StaffLoanWriteOffKind.WRITE_OFF,
            StaffLoanStatus.DISBURSED, StaffLoanWriteOffStatus.PENDING);
    private static final StaffLoanWriteOffResponse WRITTEN_OFF = response(StaffLoanWriteOffKind.WRITE_OFF,
            StaffLoanStatus.WRITTEN_OFF, StaffLoanWriteOffStatus.APPROVED);
    private static final StaffLoanWriteOffResponse REVERSED = response(StaffLoanWriteOffKind.WRITE_OFF_REVERSAL,
            StaffLoanStatus.DISBURSED, StaffLoanWriteOffStatus.APPROVED);

    private StaffLoanWriteOffService service;
    private MockMvc mvc;

    private static StaffLoanWriteOffResponse response(StaffLoanWriteOffKind kind, StaffLoanStatus loanStatus,
                                                      StaffLoanWriteOffStatus status) {
        boolean decided = status != StaffLoanWriteOffStatus.PENDING;
        return new StaffLoanWriteOffResponse(1L, 151L, "SGL-2026-000151", "E1001", "Nyasha Dube", loanStatus, kind,
                new BigDecimal("250.00"), "USD", REASON, status, "credit1", AT, decided ? "finance1" : null,
                decided ? AT.plusHours(4) : null, null);
    }

    @BeforeEach
    void setUp() {
        service = mock(StaffLoanWriteOffService.class);
        ProxyFactory secured = new ProxyFactory(new StaffLoanWriteOffController(service));
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
    @DisplayName("Credit, Finance and SUPER_ADMIN propose (201); Human Capital, agents and tills cannot; fields are"
            + " validated, and the service's refusals come through")
    void propose() throws Exception {
        when(service.propose(any())).thenReturn(PENDING);

        for (String role : List.of("HUMAN_CAPITAL", "AGENTS", "MERCHANT_TILL", "VOUCHER_SUPPORT")) {
            mvc.perform(post("/lending/v1/staff-loan-write-offs").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(StaffLoanWriteOffApiExamples.PROPOSAL))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "SUPER_ADMIN")) {
            mvc.perform(post("/lending/v1/staff-loan-write-offs").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(StaffLoanWriteOffApiExamples.PROPOSAL))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("CREATED"))
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.loanStatus").value("DISBURSED"))
                    .andExpect(jsonPath("$.data.amount").value(250.00))
                    .andExpect(jsonPath("$.data.decidedBy").doesNotExist());
        }
        mvc.perform(post("/lending/v1/staff-loan-write-offs").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"staffLoanId\": 151, \"reason\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.kind").value("Kind is required (WRITE_OFF or WRITE_OFF_REVERSAL)"))
                .andExpect(jsonPath("$.data.reason").value("Reason is required"));
        mvc.perform(post("/lending/v1/staff-loan-write-offs").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffLoanId\": 151, \"kind\": \"FORGIVE\", \"reason\": \"x\"}"))
                .andExpect(status().isBadRequest());

        doThrow(new ConflictException("Staff loan SGL-2026-000158 has not been paid out: cancel it instead of writing"
                + " it off")).when(service).propose(any());
        mvc.perform(post("/lending/v1/staff-loan-write-offs").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffLoanWriteOffApiExamples.PROPOSAL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Staff loan SGL-2026-000158 has not been paid out: cancel it"
                        + " instead of writing it off"));
    }

    @Test
    @DisplayName("only Finance and SUPER_ADMIN decide, with a message for each outcome; Credit proposes but never"
            + " decides")
    void decisions() throws Exception {
        when(service.decide(eq(1L), any())).thenReturn(WRITTEN_OFF);
        when(service.decide(eq(2L), any())).thenReturn(REVERSED);

        for (String role : List.of("CREDIT_MANAGER", "HUMAN_CAPITAL")) {
            mvc.perform(post("/lending/v1/staff-loan-write-offs/1/decision").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(StaffLoanWriteOffApiExamples.APPROVAL))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        mvc.perform(post("/lending/v1/staff-loan-write-offs/1/decision").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffLoanWriteOffApiExamples.APPROVAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Staff loan SGL-2026-000151 written off"))
                .andExpect(jsonPath("$.data.loanStatus").value("WRITTEN_OFF"));
        mvc.perform(post("/lending/v1/staff-loan-write-offs/2/decision").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffLoanWriteOffApiExamples.APPROVAL))
                .andExpect(jsonPath("$.message").value("Staff loan SGL-2026-000151's write-off reversed: it is"
                        + " DISBURSED again"));
        mvc.perform(post("/lending/v1/staff-loan-write-offs/1/decision").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"REJECTED\", \"comment\": \"Recovery still under way\"}"))
                .andExpect(jsonPath("$.message").value("Write-off request rejected"));
        mvc.perform(post("/lending/v1/staff-loan-write-offs/1/decision").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.decision").value("Decision is required (APPROVED or REJECTED)"));
    }

    @Test
    @DisplayName("proposers withdraw; staff read, with the filters reaching the service; agents are refused")
    void withdrawAndRead() throws Exception {
        when(service.withdraw(3L)).thenThrow(new ConflictException("Write-off request 3 is already approved"));
        when(service.requests(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(WRITTEN_OFF),
                PageRequest.of(0, 20), 1));

        mvc.perform(delete("/lending/v1/staff-loan-write-offs/3").with(as("HUMAN_CAPITAL")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/lending/v1/staff-loan-write-offs/3").with(as("CREDIT_MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Write-off request 3 is already approved"));

        mvc.perform(get("/lending/v1/staff-loan-write-offs").with(as("AGENTS"))).andExpect(status().isForbidden());
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "HUMAN_CAPITAL", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/staff-loan-write-offs").with(as(role))).andExpect(status().isOk());
        }
        mvc.perform(get("/lending/v1/staff-loan-write-offs").param("status", "APPROVED").param("kind", "WRITE_OFF")
                        .param("staffLoanId", "151").param("page", "1").with(as("HUMAN_CAPITAL")))
                .andExpect(jsonPath("$.data.items[0].loanReference").value("SGL-2026-000151"));
        verify(service).requests(eq(StaffLoanWriteOffStatus.APPROVED), eq(StaffLoanWriteOffKind.WRITE_OFF),
                eq(151L), eq(PageRequest.of(1, 20)));
        mvc.perform(get("/lending/v1/staff-loan-write-offs").param("kind", "FORGIVE").with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'kind'"));
    }
}
