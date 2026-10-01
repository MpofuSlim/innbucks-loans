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
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.staff.StaffFields;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliationResponse;
import zw.co.innbucks.loans.core.staff.StaffRegisterReconciliationService;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceKind;
import zw.co.innbucks.loans.core.staff.StaffRegisterVarianceResponse;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reconciliation endpoints (FR-SGL-008): Human Capital and SUPER_ADMIN run one, staff read the reports, agents see
 * nothing, and the report reaches the client shaped by its kind. Behind production's {@code @PreAuthorize}
 * interceptor; no database, no Spring context.
 */
class StaffRegisterReconciliationWebContractTest {

    private static final StaffRegisterReconciliationResponse RECONCILED = new StaffRegisterReconciliationResponse(1L,
            "payroll-master-2026-10.csv", "hc1", LocalDateTime.of(2026, 10, 1, 14, 20, 5),
            "October payroll master from Human Capital",
            List.of(StaffFields.FULL_NAME, StaffFields.GRADE, StaffFields.DEPARTMENT, StaffFields.EMPLOYMENT_STATUS),
            6, 3, 0, 1, 1, 1, 1, 1, 1, 1, 1, 6, List.of("Pay Point"));
    private static final StaffRegisterReconciliationResponse CLEAN = new StaffRegisterReconciliationResponse(2L,
            "payroll-master-2026-11.csv", "hc1", LocalDateTime.of(2026, 11, 2, 8, 0), null, List.of(), 2, 2, 2, 0,
            0, 0, 0, 0, 0, 0, 0, 0, List.of());

    private StaffRegisterReconciliationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffRegisterReconciliationService.class);
        ProxyFactory secured = new ProxyFactory(new StaffRegisterReconciliationController(service));
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
    @DisplayName("Human Capital and SUPER_ADMIN run one (201, counting the variances); credit, finance and agents cannot")
    void reconcile() throws Exception {
        when(service.reconcile(any())).thenReturn(RECONCILED);

        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "AGENTS")) {
            mvc.perform(post("/lending/v1/staff-register/reconciliations").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_RECONCILIATION_REQUEST))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        for (String role : List.of("HUMAN_CAPITAL", "SUPER_ADMIN")) {
            mvc.perform(post("/lending/v1/staff-register/reconciliations").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_RECONCILIATION_REQUEST))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("CREATED"))
                    .andExpect(jsonPath("$.message").value("Payroll master reconciled: 6 variances"))
                    .andExpect(jsonPath("$.data.leftOnPayrollEligible").value(1))
                    .andExpect(jsonPath("$.data.notOnPayrollEligible").value(1))
                    .andExpect(jsonPath("$.data.comparedFields[3]").value("employmentStatus"))
                    .andExpect(jsonPath("$.data.ignoredColumns[0]").value("Pay Point"));
        }

        when(service.reconcile(any())).thenReturn(CLEAN);
        mvc.perform(post("/lending/v1/staff-register/reconciliations").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_RECONCILIATION_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Payroll master reconciled: the register matches it"))
                .andExpect(jsonPath("$.data.comment").doesNotExist())
                .andExpect(jsonPath("$.data.comparedFields").isEmpty());
    }

    @Test
    @DisplayName("a missing field and a file the service cannot use are 400s in the envelope")
    void refused() throws Exception {
        when(service.reconcile(any())).thenThrow(new ValidationException("The file has no column for employeeNumber."
                + " The columns it needs are: employee number"));

        mvc.perform(post("/lending/v1/staff-register/reconciliations").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fileName\": \"payroll.csv\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.content").value("File content is required"));
        mvc.perform(post("/lending/v1/staff-register/reconciliations").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_RECONCILIATION_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("The file has no column for employeeNumber. The columns it"
                        + " needs are: employee number"));
    }

    @Test
    @DisplayName("staff read the reports; agents are refused; kind, paging and the 404 reach the client")
    void reads() throws Exception {
        when(service.reconciliations(any())).thenReturn(new PageImpl<>(List.of(RECONCILED), PageRequest.of(0, 20), 1));
        when(service.reconciliation(1L)).thenReturn(RECONCILED);
        when(service.reconciliation(99L)).thenThrow(new NotFoundException("Staff register reconciliation 99 not found"));
        when(service.variances(anyLong(), any(), any())).thenReturn(new PageImpl<>(List.of(
                new StaffRegisterVarianceResponse(1L, StaffRegisterVarianceKind.NOT_ON_PAYROLL, "E1001",
                        "Nyasha Dube", null, true, Map.of(StaffFields.GRADE, "C4"), null, null, null),
                new StaffRegisterVarianceResponse(2L, StaffRegisterVarianceKind.DIFFERENT, "E1043", "Tendai Moyo",
                        List.of(2), null, null, null, List.of(new StaffRegisterVarianceResponse.Difference(
                        StaffFields.GRADE, "C4", "C5", "Grade C5 is not in the grade-to-limit matrix")), null)),
                PageRequest.of(0, 20), 2));

        for (String path : List.of("/lending/v1/staff-register/reconciliations",
                "/lending/v1/staff-register/reconciliations/1",
                "/lending/v1/staff-register/reconciliations/1/variances")) {
            mvc.perform(get(path).with(as("AGENTS"))).andExpect(status().isForbidden());
            for (String role : List.of("HUMAN_CAPITAL", "CREDIT_MANAGER", "FINANCE", "SUPER_ADMIN")) {
                mvc.perform(get(path).with(as(role))).andExpect(status().isOk());
            }
        }
        mvc.perform(get("/lending/v1/staff-register/reconciliations/1/variances")
                        .param("kind", "NOT_ON_PAYROLL").param("page", "1").param("size", "50").with(as("FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].eligible").value(true))
                .andExpect(jsonPath("$.data.items[0].register.grade").value("C4"))
                .andExpect(jsonPath("$.data.items[0].rowNumbers").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].differences").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].rowNumbers[0]").value(2))
                .andExpect(jsonPath("$.data.items[1].eligible").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].differences[0].payroll").value("C5"))
                .andExpect(jsonPath("$.data.items[1].differences[0].note")
                        .value("Grade C5 is not in the grade-to-limit matrix"));
        verify(service).variances(eq(1L), eq(StaffRegisterVarianceKind.NOT_ON_PAYROLL), eq(PageRequest.of(1, 50)));
        mvc.perform(get("/lending/v1/staff-register/reconciliations/1/variances").param("kind", "MISSING")
                        .with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'kind'"));
        mvc.perform(get("/lending/v1/staff-register/reconciliations/99").with(as("FINANCE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Staff register reconciliation 99 not found"));
    }
}
