package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.workflow.Entitlement;
import zw.co.innbucks.loans.core.workflow.StageRole;
import zw.co.innbucks.loans.core.workflow.WorkItemResponse;
import zw.co.innbucks.loans.core.workflow.WorkQueueService;
import zw.co.innbucks.loans.core.workflow.WorkflowPipelineReportResponse;
import zw.co.innbucks.loans.core.workflow.WorkflowReportService;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;
import zw.co.innbucks.loans.core.workflow.WorkflowStageService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The workflow endpoints (FR-SSB-014): staff read the stages, only SUPER_ADMIN changes one, a stage's queue is seen by
 * whoever its configuration says, and a change to that takes effect on the next request. Runs behind production's
 * {@code @PreAuthorize} interceptor with the stages as seeded; no database, no Spring context.
 */
class WorkflowWebContractTest {

    private static final String TIGHTENED = """
            {"name": "Credit decision", "assignment": "EXCLUSIVE", "viewRoles": ["CREDIT_MANAGER", "FINANCE"],
             "workRoles": ["CREDIT_MANAGER"], "assignRoles": ["CREDIT_MANAGER"], "targetHours": 8,
             "escalationHours": 16, "escalateTo": ["SUPER_ADMIN"], "notifyAssignee": true}""";

    private Map<String, WorkflowStage> stages;
    private WorkflowStageService stageService;
    private WorkQueueService queueService;
    private WorkflowReportService reportService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        stages = WorkflowTestSupport.seededStages();
        stageService = mock(WorkflowStageService.class);
        queueService = mock(WorkQueueService.class);
        reportService = mock(WorkflowReportService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(
                        secured(new WorkflowStageController(stageService)),
                        secured(new WorkQueueController(queueService, reportService)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private Object secured(Object controller) {
        ProxyFactory secured = new ProxyFactory(controller);
        secured.setProxyTargetClass(true);
        secured.addAdvisor(WorkflowTestSupport.preAuthorize(WorkflowTestSupport.accessOver(stages)));
        return secured.getProxy();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static RequestPostProcessor as(String role) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
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
    @DisplayName("staff read the stages; an agent cannot")
    void stagesReadByStaff() throws Exception {
        when(stageService.list()).thenReturn(List.of());

        mvc.perform(get("/lending/v1/workflow-stages").with(as("AGENTS"))).andExpect(status().isForbidden());
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/workflow-stages").with(as(role))).andExpect(status().isOk());
        }
        when(stageService.get("BOOKING")).thenThrow(new NotFoundException("No workflow stage BOOKING"));
        mvc.perform(get("/lending/v1/workflow-stages/BOOKING").with(as("CREDIT_MANAGER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No workflow stage BOOKING"));
    }

    @Test
    @DisplayName("only SUPER_ADMIN changes a stage: Credit cannot set its own entitlements or target")
    void onlySuperAdminChangesAStage() throws Exception {
        mvc.perform(put("/lending/v1/workflow-stages/CREDIT_DECISION").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(TIGHTENED))
                .andExpect(status().isForbidden());
        verifyNoInteractions(stageService);

        mvc.perform(put("/lending/v1/workflow-stages/CREDIT_DECISION").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(TIGHTENED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Workflow stage updated; it applies to items already waiting as well"));
        verify(stageService).update(eq("CREDIT_DECISION"), any());
    }

    @Test
    @DisplayName("a missing field, a refused role or an unknown stage is refused with the service's words")
    void badStageChangesRefused() throws Exception {
        mvc.perform(put("/lending/v1/workflow-stages/CREDIT_DECISION").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Credit decision\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.targetHours").value("Target hours is required"));

        when(stageService.update(eq("CREDIT_DECISION"), any())).thenThrow(
                new IllegalArgumentException("AGENTS originate applications and cannot be given a workflow stage"));
        mvc.perform(put("/lending/v1/workflow-stages/CREDIT_DECISION").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(TIGHTENED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("AGENTS originate applications and cannot be given a workflow stage"));
    }

    @Test
    @DisplayName("a stage's items are seen by whoever the stage says, and a change applies on the next request")
    void itemsSeenAsConfigured() throws Exception {
        when(queueService.items(any(), any())).thenReturn(List.of());

        mvc.perform(get("/lending/v1/work-queues/CREDIT_DECISION/items").with(as("FINANCE")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/lending/v1/work-queues/DEDUCTION_CANCELLATION/items").with(as("FINANCE")))
                .andExpect(status().isOk());
        mvc.perform(get("/lending/v1/work-queues/CREDIT_DECISION/items").with(as("CREDIT_MANAGER"))
                        .param("assignedTo", "none"))
                .andExpect(status().isOk());
        verify(queueService).items("CREDIT_DECISION", "none");

        stages.get("CREDIT_DECISION").getRoles().add(new StageRole(UserGroup.FINANCE, Entitlement.VIEW));
        mvc.perform(get("/lending/v1/work-queues/CREDIT_DECISION/items").with(as("FINANCE")))
                .andExpect(status().isOk());

        mvc.perform(get("/lending/v1/work-queues/BOOKING/items").with(as("CREDIT_MANAGER")))
                .andExpect(status().isForbidden());
        when(queueService.items(eq("BOOKING"), isNull())).thenThrow(new NotFoundException("No workflow stage BOOKING"));
        mvc.perform(get("/lending/v1/work-queues/BOOKING/items").with(as("SUPER_ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/lending/v1/work-queues/MORE_INFORMATION/items").with(as("AGENTS")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the queues and my work are staff's; agents are refused")
    void queuesAreStaffs() throws Exception {
        when(queueService.summaries()).thenReturn(List.of());
        when(queueService.mine()).thenReturn(List.of());

        for (String path : List.of("/lending/v1/work-queues", "/lending/v1/work-queues/mine",
                "/lending/v1/loans/42/work-history")) {
            mvc.perform(get(path).with(as("AGENTS"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(as("FINANCE"))).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("assigning names the assignee; the service's refusals come through as 403, 409 and 400")
    void assignment() throws Exception {
        WorkItemResponse item = new WorkItemResponse("CREDIT_DECISION", 42L, "000000042", null, null, null, null,
                null, null, null, null, null, null, false, null, "cmanager", null, null);
        when(queueService.assign("CREDIT_DECISION", 42L, "cmanager")).thenReturn(item);
        when(queueService.assign("CREDIT_DECISION", 42L, null)).thenReturn(item);

        mvc.perform(put("/lending/v1/work-queues/CREDIT_DECISION/items/42/assignment").with(as("AGENTS"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/lending/v1/work-queues/CREDIT_DECISION/items/42/assignment").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assignee\": \"cmanager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Assigned to cmanager"))
                .andExpect(jsonPath("$.data.assignedTo").value("cmanager"));
        mvc.perform(put("/lending/v1/work-queues/CREDIT_DECISION/items/42/assignment").with(as("CREDIT_MANAGER")))
                .andExpect(status().isOk());

        when(queueService.assign("CREDIT_DECISION", 43L, "rnyathi")).thenThrow(new AccessDeniedException(
                "You may take Credit decision items for yourself, but not give them to others"));
        mvc.perform(put("/lending/v1/work-queues/CREDIT_DECISION/items/43/assignment").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assignee\": \"rnyathi\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("You may take Credit decision items for yourself, but not give them to others"));
        when(queueService.assign("CREDIT_DECISION", 44L, null)).thenThrow(
                new ConflictException("Loan 000000044 is not waiting at Credit decision"));
        mvc.perform(put("/lending/v1/work-queues/CREDIT_DECISION/items/44/assignment").with(as("CREDIT_MANAGER")))
                .andExpect(status().isConflict());
        when(queueService.assign("CREDIT_DECISION", 45L, "fmoyo")).thenThrow(
                new IllegalArgumentException("fmoyo does not work Credit decision"));
        mvc.perform(put("/lending/v1/work-queues/CREDIT_DECISION/items/45/assignment").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assignee\": \"fmoyo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("fmoyo does not work Credit decision"));

        mvc.perform(delete("/lending/v1/work-queues/CREDIT_DECISION/items/42/assignment").with(as("CREDIT_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Released"));
        verify(queueService).release("CREDIT_DECISION", 42L);
    }

    @Test
    @DisplayName("the pipeline report is staff's, and an inverted period is a 400")
    void pipelineReport() throws Exception {
        when(reportService.pipeline(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(new WorkflowPipelineReportResponse(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                        List.of()));
        when(reportService.pipeline(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
                .thenThrow(new ValidationException("fromDate must not be after toDate"));

        mvc.perform(get("/lending/v1/reports/workflow-pipeline").with(as("AGENTS"))).andExpect(status().isForbidden());
        mvc.perform(get("/lending/v1/reports/workflow-pipeline").with(as("FINANCE"))
                        .param("fromDate", "2026-09-01").param("toDate", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromDate").value("2026-09-01"));
        mvc.perform(get("/lending/v1/reports/workflow-pipeline").with(as("CREDIT_MANAGER"))
                        .param("fromDate", "2026-09-30").param("toDate", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("fromDate must not be after toDate"));
    }

    @Test
    @DisplayName("the seeded-stages example is the configuration the migration seeds")
    void seededExampleMatchesTheSeeds() {
        JsonNode examples = JsonMapper.builder().build().readTree(ApiExamples.WORKFLOW_STAGES).path("data");
        Map<String, WorkflowStage> seeded = WorkflowTestSupport.seededStages();
        assertThat(examples.size()).isEqualTo(seeded.size());
        for (JsonNode example : examples) {
            WorkflowStage stage = seeded.get(example.path("code").asString());
            assertThat(stage).as(example.path("code").asString()).isNotNull();
            assertThat(example.path("targetHours").asInt()).isEqualTo(stage.getTargetHours());
            assertThat(example.path("escalationHours").asInt()).isEqualTo(stage.getEscalationHours());
            assertThat(example.path("assignment").asString()).isEqualTo(stage.getAssignment().name());
            for (UserGroup group : EnumSet.of(UserGroup.CREDIT_MANAGER, UserGroup.FINANCE)) {
                assertThat(listed(example.path("viewRoles"), group)).as(stage.getCode() + " view " + group)
                        .isEqualTo(stage.grants(Set.of(group), Entitlement.VIEW));
                if (!"MORE_INFORMATION".equals(stage.getCode())) {
                    assertThat(listed(example.path("workRoles"), group)).as(stage.getCode() + " work " + group)
                            .isEqualTo(stage.grants(Set.of(group), Entitlement.WORK));
                    assertThat(listed(example.path("assignRoles"), group)).as(stage.getCode() + " assign " + group)
                            .isEqualTo(stage.grants(Set.of(group), Entitlement.ASSIGN));
                }
            }
        }
    }

    private static boolean listed(JsonNode roles, UserGroup group) {
        for (JsonNode role : roles) {
            if (group.name().equals(role.asString())) {
                return true;
            }
        }
        return false;
    }
}
