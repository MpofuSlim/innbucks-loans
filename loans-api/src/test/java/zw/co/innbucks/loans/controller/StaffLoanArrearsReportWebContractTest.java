package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanArrearsReport;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanArrearsService;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The arrears report endpoint (FR-SGL-045): Credit, Finance, Human Capital and SUPER_ADMIN read it, as JSON or as a
 * spreadsheet named for the day; anyone else, and an unknown format, is refused in the envelope.
 */
class StaffLoanArrearsReportWebContractTest {

    private StaffLoanArrearsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffLoanArrearsService.class);
        when(service.report()).thenReturn(new StaffLoanArrearsReport(LocalDate.of(2026, 12, 2),
                LocalDateTime.of(2026, 12, 2, 5, 0, 1), 0, 30, List.of(), List.of()));
        when(service.csv()).thenReturn(new StaffLoanArrearsService.Csv(LocalDate.of(2026, 12, 2),
                "reference,employeeNumber\r\n"));
        ProxyFactory secured = new ProxyFactory(new StaffLoanArrearsReportController(service));
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
    @DisplayName("staff with a stake read it; agents, tills and voucher support cannot")
    void roles() throws Exception {
        for (String role : List.of("AGENTS", "MERCHANT_TILL", "VOUCHER_SUPPORT")) {
            mvc.perform(get("/lending/v1/staff-loan-arrears-report").with(as(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "HUMAN_CAPITAL", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/staff-loan-arrears-report").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.data.escalationDays").value(30))
                    .andExpect(jsonPath("$.data.lines").isArray());
        }
    }

    @Test
    @DisplayName("format=csv downloads it, named for the day; another format is a 400")
    void formats() throws Exception {
        mvc.perform(get("/lending/v1/staff-loan-arrears-report").param("format", "CSV").with(as("HUMAN_CAPITAL")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"staff-loan-arrears-2026-12-02.csv\""))
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(content().string("reference,employeeNumber\r\n"));
        mvc.perform(get("/lending/v1/staff-loan-arrears-report").param("format", "xlsx").with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("format must be json or csv"));
    }
}
