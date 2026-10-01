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
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffFields;
import zw.co.innbucks.loans.core.staff.StaffRecordInvalidException;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchResponse;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchSource;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchStatus;
import zw.co.innbucks.loans.core.staff.StaffRegisterRowOutcome;
import zw.co.innbucks.loans.core.staff.StaffRegisterRowResponse;
import zw.co.innbucks.loans.core.staff.StaffRegisterService;
import zw.co.innbucks.loans.core.staff.StaffUploadRejectedException;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The staff register endpoints (FR-SGL-001 to FR-SGL-007): Human Capital and SUPER_ADMIN submit and decide, staff
 * read, agents see nothing, and every refusal reaches the client in the envelope. Behind production's
 * {@code @PreAuthorize} interceptor; no database, no Spring context.
 */
class StaffRegisterWebContractTest {

    private static final StaffRegisterBatchResponse PENDING = new StaffRegisterBatchResponse(12L,
            StaffRegisterBatchSource.UPLOAD, "staff-register-2026-10.csv", StaffRegisterBatchStatus.PENDING, "hc1",
            LocalDateTime.of(2026, 10, 1, 8, 15), "October register from Human Capital", 3, 2, 1, null, null, null,
            null, null, null, null, List.of("Cost Centre"), List.of());
    private static final StaffRegisterBatchResponse APPROVED = new StaffRegisterBatchResponse(12L,
            StaffRegisterBatchSource.UPLOAD, "staff-register-2026-10.csv", StaffRegisterBatchStatus.APPROVED, "hc1",
            LocalDateTime.of(2026, 10, 1, 8, 15), null, 3, 2, 1, "hc2", LocalDateTime.of(2026, 10, 1, 9, 2, 40),
            null, 1, 1, 0, 0, null, null);

    private StaffRegisterService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffRegisterService.class);
        ProxyFactory secured = new ProxyFactory(new StaffRegisterController(service));
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
    @DisplayName("Human Capital and SUPER_ADMIN submit a file (201); credit, finance and agents cannot")
    void upload() throws Exception {
        when(service.upload(any())).thenReturn(PENDING);

        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "AGENTS")) {
            mvc.perform(post("/lending/v1/staff-register/uploads").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_REGISTER_UPLOAD_REQUEST))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        for (String role : List.of("HUMAN_CAPITAL", "SUPER_ADMIN")) {
            mvc.perform(post("/lending/v1/staff-register/uploads").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_REGISTER_UPLOAD_REQUEST))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("CREATED"))
                    .andExpect(jsonPath("$.message").value("Staff register file submitted; it reaches the register"
                            + " once someone else approves it"))
                    .andExpect(jsonPath("$.data.ignoredColumns[0]").value("Cost Centre"))
                    .andExpect(jsonPath("$.data.decidedBy").doesNotExist());
        }
        mvc.perform(post("/lending/v1/staff-register/uploads").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fileName\": \"a.csv\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.content").value("File content is required"));
    }

    @Test
    @DisplayName("a file whose every row was refused is a 400 NOTHING_TO_LOAD carrying each row's reasons")
    void nothingToLoad() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(StaffFields.EMPLOYEE_NUMBER, "E1045");
        values.put(StaffFields.GRADE, "C9");
        when(service.upload(any())).thenThrow(new StaffUploadRejectedException("staff-register-2026-10.csv",
                List.of(new StaffRegisterRowResponse(4, StaffRegisterRowOutcome.REJECTED, null, values,
                        Map.of(StaffFields.GRADE, "Grade C9 is not in the grade-to-limit matrix"), null))));
        when(service.submit(any())).thenThrow(new ValidationException("The file has no column for grade"));

        mvc.perform(post("/lending/v1/staff-register/uploads").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_REGISTER_UPLOAD_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_LOAD"))
                .andExpect(jsonPath("$.message")
                        .value("No row of staff-register-2026-10.csv can be loaded; all 1 were refused"))
                .andExpect(jsonPath("$.data.rejected[0].rowNumber").value(4))
                .andExpect(jsonPath("$.data.rejected[0].values.grade").value("C9"))
                .andExpect(jsonPath("$.data.rejected[0].errors.grade")
                        .value("Grade C9 is not in the grade-to-limit matrix"));
    }

    @Test
    @DisplayName("a single record the rules refuse is a 400 VALIDATION_ERROR naming every field")
    void singleRecordRefused() throws Exception {
        when(service.submit(any())).thenThrow(new StaffRecordInvalidException(Map.of(
                StaffFields.GRADE, "Grade C9 is not in the grade-to-limit matrix",
                StaffFields.MOBILE_NUMBER, "Mobile number 263782606983 already belongs to employee E1001")));

        mvc.perform(post("/lending/v1/staff-register/changes").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_RECORD_REQUEST))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/staff-register/changes").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_RECORD_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.data.grade").value("Grade C9 is not in the grade-to-limit matrix"))
                .andExpect(jsonPath("$.data.mobileNumber")
                        .value("Mobile number 263782606983 already belongs to employee E1001"));
    }

    @Test
    @DisplayName("deciding: the message counts what approval did; the submitter's own decision is a 403")
    void decide() throws Exception {
        when(service.decide(eq(12L), any())).thenReturn(APPROVED);
        when(service.decide(eq(13L), any())).thenThrow(new AccessDeniedException("hc1 submitted staff register"
                + " batch 13 and cannot also approve or reject it; someone else in Human Capital or a SUPER_ADMIN must"));

        mvc.perform(post("/lending/v1/staff-register/batches/12/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_REGISTER_APPROVAL))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/staff-register/batches/12/decision").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_REGISTER_APPROVAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Staff register batch approved: 1 added, 1 changed, 0 unchanged, 0 skipped"))
                .andExpect(jsonPath("$.data.createdRows").value(1))
                .andExpect(jsonPath("$.data.ignoredColumns").doesNotExist());
        mvc.perform(post("/lending/v1/staff-register/batches/13/decision").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_REGISTER_APPROVAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("hc1 submitted staff register batch 13 and cannot also"
                        + " approve or reject it; someone else in Human Capital or a SUPER_ADMIN must"));
        mvc.perform(post("/lending/v1/staff-register/batches/12/decision").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"REJECTED\","
                                + " \"comment\": \"Wrong file\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Staff register batch rejected"));
        mvc.perform(post("/lending/v1/staff-register/batches/12/decision").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.decision").value("Decision is required (APPROVED or REJECTED)"));
    }

    @Test
    @DisplayName("staff read batches, rows and the register; agents are refused; paging and filters reach the service")
    void reads() throws Exception {
        when(service.batches(any(), any())).thenReturn(new PageImpl<>(List.of(PENDING), PageRequest.of(0, 20), 1));
        when(service.rows(anyLong(), any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(service.members(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(service.history(any())).thenReturn(List.of());

        for (String path : List.of("/lending/v1/staff-register/batches", "/lending/v1/staff-register/batches/12/rows",
                "/lending/v1/staff-members", "/lending/v1/staff-members/E1043/history")) {
            mvc.perform(get(path).with(as("AGENTS"))).andExpect(status().isForbidden());
            for (String role : List.of("HUMAN_CAPITAL", "CREDIT_MANAGER", "FINANCE", "SUPER_ADMIN")) {
                mvc.perform(get(path).with(as(role))).andExpect(status().isOk());
            }
        }
        mvc.perform(get("/lending/v1/staff-register/batches").param("status", "PENDING").param("size", "500")
                        .with(as("FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(12))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        verify(service).batches(eq(StaffRegisterBatchStatus.PENDING), eq(PageRequest.of(0, 100)));
        mvc.perform(get("/lending/v1/staff-members").param("status", "ACTIVE").param("grade", "c4")
                        .param("department", "fin").param("q", "moyo").with(as("FINANCE")))
                .andExpect(status().isOk());
        verify(service).members(eq(StaffEmploymentStatus.ACTIVE), eq("c4"), eq("fin"), eq("moyo"), any());
        mvc.perform(get("/lending/v1/staff-register/batches/12/rows").param("outcome", "STAGED")
                        .with(as("FINANCE")))
                .andExpect(status().isOk());
        verify(service).rows(eq(12L), eq(StaffRegisterRowOutcome.STAGED), any());
        mvc.perform(get("/lending/v1/staff-members").param("status", "RETIRED").with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'status'"));
    }

    @Test
    @DisplayName("an unknown employee or batch is a 404 with the service's words; withdrawing is for makers only")
    void notFoundAndWithdraw() throws Exception {
        when(service.member("E9999")).thenThrow(new NotFoundException("Employee E9999 is not on the staff register"));
        when(service.withdraw(13L)).thenReturn(PENDING);

        mvc.perform(get("/lending/v1/staff-members/E9999").with(as("FINANCE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Employee E9999 is not on the staff register"));
        mvc.perform(delete("/lending/v1/staff-register/batches/13").with(as("CREDIT_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/lending/v1/staff-register/batches/13").with(as("HUMAN_CAPITAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Staff register batch withdrawn"));
    }
}
