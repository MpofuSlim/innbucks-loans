package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferOrigin;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferResponse;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunFailedException;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunResponse;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunService;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunStatus;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunTrigger;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRunner;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferScheduleResponse;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferService;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferStatus;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The offer endpoints (FR-SGL-015 to FR-SGL-018): Credit and SUPER_ADMIN start a run, staff read runs and offers,
 * agents see nothing, a refused run is a 409 that still carries the recorded attempt. Behind production's
 * {@code @PreAuthorize} interceptor; no database, no Spring context.
 */
class StaffOfferWebContractTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 5, 7, 30, 12);
    private static final StaffOfferRunResponse COMPLETED = new StaffOfferRunResponse(2L, LocalDate.of(2026, 10, 5),
            StaffOfferRunTrigger.MANUAL, "credit1", AT, AT, StaffOfferRunStatus.COMPLETED, null, 2L, 3, 1, 0, 0, 0, 0,
            2, 0, 0, 2, 0, 0);
    private static final String STALE = "The staff register was last reconciled against the payroll master on"
            + " 2026-10-02, 38 days ago; offers need a reconciliation within the last 35 days";
    private static final StaffOfferRunResponse REFUSED = new StaffOfferRunResponse(3L, LocalDate.of(2026, 11, 9),
            StaffOfferRunTrigger.MANUAL, "credit1", AT, AT, StaffOfferRunStatus.REFUSED, STALE, 2L, null, null, null,
            null, null, null, null, null, null, null, null, null);

    private StaffOfferRunner runner;
    private StaffOfferRunService runService;
    private StaffOfferService offerService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runner = mock(StaffOfferRunner.class);
        runService = mock(StaffOfferRunService.class);
        offerService = mock(StaffOfferService.class);
        ProxyFactory secured = new ProxyFactory(new StaffOfferController(runner, runService, offerService));
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
    @DisplayName("Credit and SUPER_ADMIN start a run (201, counting what it did); Human Capital, finance and agents"
            + " cannot")
    void run() throws Exception {
        when(runner.runNow()).thenReturn(COMPLETED);

        for (String role : List.of("HUMAN_CAPITAL", "FINANCE", "AGENTS")) {
            mvc.perform(post("/lending/v1/staff-offer-runs").with(as(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(runner);
        for (String role : List.of("CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(post("/lending/v1/staff-offer-runs").with(as(role)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("CREATED"))
                    .andExpect(jsonPath("$.message").value("Offer run completed: 0 offered, 0 refreshed, 2 already"
                            + " held this week's offer"))
                    .andExpect(jsonPath("$.data.alreadyOffered").value(2))
                    .andExpect(jsonPath("$.data.reason").doesNotExist());
        }
    }

    @Test
    @DisplayName("a failed run is a 500 RUN_FAILED carrying the recorded attempt, not a bare internal error")
    void failed() throws Exception {
        StaffOfferRunResponse failed = new StaffOfferRunResponse(3L, LocalDate.of(2026, 10, 5),
                StaffOfferRunTrigger.MANUAL, "credit1", AT, AT.plusSeconds(30), StaffOfferRunStatus.FAILED,
                "The run failed and nothing it did was kept: QueryTimeoutException: canceling statement", null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        when(runner.runNow()).thenThrow(new StaffOfferRunFailedException(failed,
                new IllegalStateException("canceling statement")));

        mvc.perform(post("/lending/v1/staff-offer-runs").with(as("CREDIT_MANAGER")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RUN_FAILED"))
                .andExpect(jsonPath("$.message").value("The offer run failed and nothing it did was kept. It is"
                        + " recorded as run 3, with the reason; try again, and if it fails again, report run 3"))
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    @DisplayName("a refused run is a 409 RUN_REFUSED carrying the recorded attempt; one in progress is a plain 409")
    void refused() throws Exception {
        when(runner.runNow()).thenReturn(REFUSED).thenThrow(new ConflictException("An offer run is already in"
                + " progress; try again once it has finished"));

        mvc.perform(post("/lending/v1/staff-offer-runs").with(as("CREDIT_MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_REFUSED"))
                .andExpect(jsonPath("$.message").value(STALE))
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.status").value("REFUSED"))
                .andExpect(jsonPath("$.data.registerMembers").doesNotExist());
        mvc.perform(post("/lending/v1/staff-offer-runs").with(as("CREDIT_MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("staff read runs, the schedule and offers; agents are refused; filters and paging reach the service")
    void reads() throws Exception {
        when(runService.runs(any())).thenReturn(new PageImpl<>(List.of(COMPLETED), PageRequest.of(0, 20), 1));
        when(runService.run(2L)).thenReturn(COMPLETED);
        when(runService.run(99L)).thenThrow(new NotFoundException("Staff offer run 99 not found"));
        when(runService.schedule()).thenReturn(new StaffOfferScheduleResponse("0 0 8 * * MON", "Africa/Harare",
                LocalDateTime.of(2026, 10, 12, 6, 0), true, 7, 35, 2L, LocalDateTime.of(2026, 10, 2, 9, 40), 3L, true,
                null, COMPLETED));
        when(offerService.offers(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(
                new StaffOfferResponse(1L, "E1001", "Nyasha Dube", "C4", "Band C", new BigDecimal("300.00"), 1L,
                        null, LocalDate.of(2026, 10, 5), 1L, StaffOfferOrigin.RUN, StaffOfferStatus.ACTIVE, AT, AT.plusDays(7), null, null,
                        null)), PageRequest.of(0, 20), 1));

        for (String path : List.of("/lending/v1/staff-offer-runs", "/lending/v1/staff-offer-runs/2",
                "/lending/v1/staff-offer-schedule", "/lending/v1/staff-offers")) {
            mvc.perform(get(path).with(as("AGENTS"))).andExpect(status().isForbidden());
            for (String role : List.of("CREDIT_MANAGER", "FINANCE", "HUMAN_CAPITAL", "SUPER_ADMIN")) {
                mvc.perform(get(path).with(as(role))).andExpect(status().isOk());
            }
        }
        mvc.perform(get("/lending/v1/staff-offers").param("status", "ACTIVE").param("employeeNumber", "e1001")
                        .param("runId", "1").param("size", "500").with(as("FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].employeeNumber").value("E1001"))
                .andExpect(jsonPath("$.data.items[0].amount").value(300.00))
                .andExpect(jsonPath("$.data.items[0].origin").value("RUN"))
                .andExpect(jsonPath("$.data.items[0].closedReason").doesNotExist());
        verify(offerService).offers(eq(StaffOfferStatus.ACTIVE), eq("e1001"), eq(1L), eq(PageRequest.of(0, 100)));
        mvc.perform(get("/lending/v1/staff-offers").param("status", "TAKEN").with(as("FINANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'status'"));
        mvc.perform(get("/lending/v1/staff-offer-schedule").with(as("HUMAN_CAPITAL")))
                .andExpect(jsonPath("$.data.readyToRun").value(true))
                .andExpect(jsonPath("$.data.notReadyReason").doesNotExist())
                .andExpect(jsonPath("$.data.lastRun.id").value(2));
        mvc.perform(get("/lending/v1/staff-offer-runs/99").with(as("FINANCE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Staff offer run 99 not found"));
    }
}
