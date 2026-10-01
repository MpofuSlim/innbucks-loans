package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
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
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.ProposeStaffGradeLimitRequest;
import zw.co.innbucks.loans.core.staff.StaffGradeLimit;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitChangeResponse;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitChangeStatus;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitDecision;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitDecisionRequest;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitResponse;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The grade-to-limit matrix endpoints (FR-SGL-009, FR-SGL-010): staff read it, only CREDIT_MANAGER and SUPER_ADMIN
 * propose, decide or withdraw a change, and the requests are validated before the service is called. Behind
 * production's {@code @PreAuthorize} interceptor; no database, no Spring context.
 */
class StaffGradeLimitWebContractTest {

    private static final StaffGradeLimit C4 = new StaffGradeLimit(1L, "C4", "Band C", new BigDecimal("300.00"),
            LocalDate.of(2026, 10, 1), "credit2", LocalDateTime.of(2026, 9, 30, 8, 5, 11));
    private static final StaffGradeLimitChangeResponse PENDING = new StaffGradeLimitChangeResponse(2L, "C4", "Band C",
            new BigDecimal("350.00"), LocalDate.of(2026, 11, 1), StaffGradeLimitChangeStatus.PENDING, "credit1",
            LocalDateTime.of(2026, 10, 1, 6, 30, 2), "Annual review approved by Credit and Human Capital", null, null,
            null, null, C4);

    private StaffGradeLimitService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffGradeLimitService.class);
        ProxyFactory secured = new ProxyFactory(new StaffGradeLimitController(service));
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
    @DisplayName("staff read the matrix and its changes; agents are refused")
    void staffRead() throws Exception {
        when(service.matrix(any())).thenReturn(List.of(new StaffGradeLimitResponse("C4", C4, List.of(), 1)));
        when(service.changes(any(), any())).thenReturn(List.of(PENDING));

        mvc.perform(get("/lending/v1/staff-grade-limits").with(as("AGENTS"))).andExpect(status().isForbidden());
        mvc.perform(get("/lending/v1/staff-grade-limit-changes").with(as("AGENTS")))
                .andExpect(status().isForbidden());
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "HUMAN_CAPITAL", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/staff-grade-limits").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].grade").value("C4"))
                    .andExpect(jsonPath("$.data[0].current.maximumLimit").value(300.00))
                    .andExpect(jsonPath("$.data[0].current.effectiveFrom").value("2026-10-01"))
                    .andExpect(jsonPath("$.data[0].pendingChanges").value(1));
            mvc.perform(get("/lending/v1/staff-grade-limit-changes").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                    .andExpect(jsonPath("$.data[0].replacing.maximumLimit").value(300.00))
                    .andExpect(jsonPath("$.data[0].decidedBy").doesNotExist());
        }
    }

    @Test
    @DisplayName("asOf, grade and status reach the service; a bad date or status is a 400")
    void queryParameters() throws Exception {
        when(service.matrix(any())).thenReturn(List.of());
        when(service.changes(any(), any())).thenReturn(List.of());

        mvc.perform(get("/lending/v1/staff-grade-limits").param("asOf", "2026-11-01").with(as("FINANCE")))
                .andExpect(status().isOk());
        verify(service).matrix(LocalDate.of(2026, 11, 1));
        mvc.perform(get("/lending/v1/staff-grade-limits").with(as("FINANCE"))).andExpect(status().isOk());
        verify(service).matrix(isNull());
        mvc.perform(get("/lending/v1/staff-grade-limit-changes").param("grade", "c4").param("status", "PENDING")
                        .with(as("FINANCE")))
                .andExpect(status().isOk());
        verify(service).changes("c4", StaffGradeLimitChangeStatus.PENDING);

        mvc.perform(get("/lending/v1/staff-grade-limits").param("asOf", "01/11/2026").with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Invalid value for 'asOf'"));
        mvc.perform(get("/lending/v1/staff-grade-limit-changes").param("status", "WAITING").with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'status'"));
    }

    @Test
    @DisplayName("credit managers and SUPER_ADMIN propose (201); finance, Human Capital and agents cannot")
    void propose() throws Exception {
        when(service.propose(any())).thenReturn(PENDING);

        for (String role : List.of("FINANCE", "HUMAN_CAPITAL", "AGENTS")) {
            mvc.perform(post("/lending/v1/staff-grade-limit-changes").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_GRADE_LIMIT_PROPOSAL))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        for (String role : List.of("CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(post("/lending/v1/staff-grade-limit-changes").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_GRADE_LIMIT_PROPOSAL))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("CREATED"))
                    .andExpect(jsonPath("$.message")
                            .value("Grade limit change proposed; it applies once someone else approves it"))
                    .andExpect(jsonPath("$.data.id").value(2));
        }
        ArgumentCaptor<ProposeStaffGradeLimitRequest> sent = ArgumentCaptor.forClass(ProposeStaffGradeLimitRequest.class);
        verify(service, times(2)).propose(sent.capture());
        assertThat(sent.getValue().getGrade()).isEqualTo("C4");
        assertThat(sent.getValue().getMaximumLimit()).isEqualByComparingTo("350.00");
        assertThat(sent.getValue().getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("a bad proposal is a 400 naming every field, before the service is called")
    void badProposal() throws Exception {
        mvc.perform(post("/lending/v1/staff-grade-limit-changes").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"grade": "C4%", "scoreBand": "", "maximumLimit": 350.123, "effectiveFrom": null}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.grade").value("Grade must be at most 32 letters, digits, spaces, hyphens or"
                        + " slashes, e.g. C4 or CLERK/ASSISTANT/AGENT"))
                .andExpect(jsonPath("$.data.scoreBand").value("Score band is required"))
                .andExpect(jsonPath("$.data.maximumLimit").value("Maximum limit must have at most 2 decimal places"))
                .andExpect(jsonPath("$.data.effectiveFrom").value("Effective date is required"));
        mvc.perform(post("/lending/v1/staff-grade-limit-changes").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"grade": "C4", "scoreBand": "Band C", "maximumLimit": -1, "effectiveFrom": "2026-11-01"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.maximumLimit").value("Maximum limit cannot be negative"));
        mvc.perform(post("/lending/v1/staff-grade-limit-changes").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"grade": "C4", "scoreBand": "Band C", "maximumLimit": 1, "effectiveFrom": "01/11/2026"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a conflict is a 409 with the service's words")
    void proposalConflict() throws Exception {
        when(service.propose(any())).thenThrow(new ConflictException("A change to grade C4 from 2026-11-01 is already"
                + " waiting for approval (change 2); approve, reject or withdraw it first"));

        mvc.perform(post("/lending/v1/staff-grade-limit-changes").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_GRADE_LIMIT_PROPOSAL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("A change to grade C4 from 2026-11-01 is already waiting for"
                        + " approval (change 2); approve, reject or withdraw it first"));
    }

    @Test
    @DisplayName("deciding: the message names the outcome; the proposer's own decision is a 403 with the reason")
    void decide() throws Exception {
        StaffGradeLimitChangeResponse approved = new StaffGradeLimitChangeResponse(2L, "C4", "Band C",
                new BigDecimal("350.00"), LocalDate.of(2026, 11, 1), StaffGradeLimitChangeStatus.APPROVED, "credit1",
                LocalDateTime.of(2026, 10, 1, 6, 30, 2), null, "credit2", LocalDateTime.of(2026, 10, 1, 9, 20, 45),
                "Approved at the annual review", null, null);
        when(service.decide(eq(2L), any())).thenReturn(approved);

        mvc.perform(post("/lending/v1/staff-grade-limit-changes/2/decision").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_GRADE_LIMIT_APPROVAL))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/staff-grade-limit-changes/2/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_GRADE_LIMIT_APPROVAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Grade limit change approved"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decidedBy").value("credit2"));
        mvc.perform(post("/lending/v1/staff-grade-limit-changes/2/decision").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"REJECTED\", \"comment\": \"Not the signed-off figure\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Grade limit change rejected"));
        ArgumentCaptor<StaffGradeLimitDecisionRequest> sent =
                ArgumentCaptor.forClass(StaffGradeLimitDecisionRequest.class);
        verify(service, times(2)).decide(eq(2L), sent.capture());
        assertThat(sent.getAllValues()).extracting(StaffGradeLimitDecisionRequest::getDecision)
                .containsExactly(StaffGradeLimitDecision.APPROVED, StaffGradeLimitDecision.REJECTED);

        when(service.decide(eq(3L), any())).thenThrow(new AccessDeniedException("credit1 proposed grade limit change"
                + " 3 and cannot also approve or reject it; another credit manager or SUPER_ADMIN must"));
        mvc.perform(post("/lending/v1/staff-grade-limit-changes/3/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_GRADE_LIMIT_APPROVAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("credit1 proposed grade limit change 3 and cannot also approve"
                        + " or reject it; another credit manager or SUPER_ADMIN must"));
        when(service.decide(eq(99L), any())).thenThrow(new NotFoundException("Grade limit change 99 not found"));
        mvc.perform(post("/lending/v1/staff-grade-limit-changes/99/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_GRADE_LIMIT_APPROVAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Grade limit change 99 not found"));
    }

    @Test
    @DisplayName("a decision without one, or a made-up one, is a 400 before the service is called")
    void badDecision() throws Exception {
        mvc.perform(post("/lending/v1/staff-grade-limit-changes/2/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\": \"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.decision").value("Decision is required (APPROVED or REJECTED)"));
        mvc.perform(post("/lending/v1/staff-grade-limit-changes/2/decision").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\": \"APPROVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("withdrawing: credit managers and SUPER_ADMIN only; a decided change is a 409")
    void withdraw() throws Exception {
        when(service.withdraw(2L)).thenReturn(PENDING);
        when(service.withdraw(1L)).thenThrow(new ConflictException("Grade limit change 1 is already approved"));

        mvc.perform(delete("/lending/v1/staff-grade-limit-changes/2").with(as("FINANCE")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/lending/v1/staff-grade-limit-changes/2").with(as("CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Grade limit change withdrawn"));
        mvc.perform(delete("/lending/v1/staff-grade-limit-changes/1").with(as("CREDIT_MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Grade limit change 1 is already approved"));
    }
}
