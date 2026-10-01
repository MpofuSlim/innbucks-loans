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
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationBroadcastKind;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationBroadcastResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationChannel;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationDispatchStatus;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationOutboundStatus;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationQueueResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationService;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationTemplate;
import zw.co.innbucks.loans.core.staff.notification.StaffOfferMessagesResponse;
import zw.co.innbucks.loans.core.staff.notification.StaffOfferMessagesService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The staff notification endpoints (FR-SGL-019 to FR-SGL-023): who may send the launch and start sending, who may opt
 * a member out, who may read the log, and every refusal in the envelope.
 */
class StaffNotificationWebContractTest {

    private static final StaffNotificationBroadcastResponse LAUNCHED = new StaffNotificationBroadcastResponse(1L,
            StaffNotificationBroadcastKind.LAUNCH, StaffNotificationTemplate.LAUNCH, 1,
            StaffNotificationTemplate.LAUNCH.title(), StaffNotificationTemplate.LAUNCH.text(), 1240, 17, "credit1",
            LocalDateTime.of(2026, 10, 2, 7, 15, 4), null);
    private static final StaffOfferMessagesResponse OPTED_OUT = new StaffOfferMessagesResponse("E1043", "Tendai Moyo",
            true, "Asked by phone", "hc1", LocalDateTime.of(2026, 10, 1, 8, 20, 31));

    private StaffNotificationService service;
    private StaffOfferMessagesService messages;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffNotificationService.class);
        messages = mock(StaffOfferMessagesService.class);
        ProxyFactory secured = new ProxyFactory(new StaffNotificationController(service, messages));
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
    @DisplayName("only Credit and SUPER_ADMIN send the launch (201); the count is required; a second launch is a 409")
    void launch() throws Exception {
        when(service.launch(any())).thenReturn(LAUNCHED);

        for (String role : List.of("HUMAN_CAPITAL", "FINANCE", "AGENTS")) {
            mvc.perform(post("/lending/v1/staff-notification-broadcasts").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LAUNCH_REQUEST))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/lending/v1/staff-notification-broadcasts/launch-preview").with(as(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
        mvc.perform(post("/lending/v1/staff-notification-broadcasts").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LAUNCH_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("Launch announced to 1240 staff: stored in-app now, and sent"
                        + " to their phones at a measured pace"))
                .andExpect(jsonPath("$.data.recipients").value(1240));
        mvc.perform(post("/lending/v1/staff-notification-broadcasts").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.expectedRecipients").value("expectedRecipients is required: the"
                        + " recipients count from the launch preview"));

        when(service.launch(any())).thenThrow(new ConflictException("The launch was announced by credit1"
                + " (broadcast 1); it is sent once"));
        mvc.perform(post("/lending/v1/staff-notification-broadcasts").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_LAUNCH_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("The launch was announced by credit1 (broadcast 1); it is"
                        + " sent once"));
    }

    @Test
    @DisplayName("Credit and SUPER_ADMIN start sending what waits (202, with the count); readers cannot")
    void dispatchPending() throws Exception {
        when(service.dispatchPending()).thenReturn(new StaffNotificationQueueResponse(4));

        mvc.perform(post("/lending/v1/staff-notifications/dispatch").with(as("HUMAN_CAPITAL")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/lending/v1/staff-notifications/dispatch").with(as("CREDIT_MANAGER")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("ACCEPTED"))
                .andExpect(jsonPath("$.message").value("Sending 4 pending notifications"))
                .andExpect(jsonPath("$.data.pending").value(4));
    }

    @Test
    @DisplayName("the notifications, the dispatch log and the summary are for Credit, Finance, Human Capital and"
            + " SUPER_ADMIN, with their filters passed through")
    void reads() throws Exception {
        when(service.notifications(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(service.dispatches(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/lending/v1/staff-notifications").with(as("AGENTS")))
                .andExpect(status().isForbidden());
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "HUMAN_CAPITAL", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/staff-notifications").with(as(role))
                            .param("employeeNumber", "E1012").param("template", "OFFER_NEW")
                            .param("outboundStatus", "FAILED").param("runId", "1")
                            .param("fromDate", "2026-10-05").param("toDate", "2026-10-05"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items").isArray());
        }
        verify(service, times(4)).notifications(eq("E1012"), eq(StaffNotificationTemplate.OFFER_NEW),
                eq(StaffNotificationOutboundStatus.FAILED), eq(1L), isNull(), eq(LocalDate.of(2026, 10, 5)),
                eq(LocalDate.of(2026, 10, 5)), any());

        mvc.perform(get("/lending/v1/staff-notification-dispatches").with(as("FINANCE"))
                        .param("channel", "WHATSAPP").param("status", "SENT").param("broadcastId", "1"))
                .andExpect(status().isOk());
        verify(service).dispatches(isNull(), eq(StaffNotificationChannel.WHATSAPP),
                eq(StaffNotificationDispatchStatus.SENT), isNull(), isNull(), eq(1L), isNull(), isNull(), any());
        mvc.perform(get("/lending/v1/staff-notification-dispatches").with(as("FINANCE")).param("channel", "FAX"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        mvc.perform(get("/lending/v1/staff-notifications/summary").with(as("HUMAN_CAPITAL")).param("runId", "1"))
                .andExpect(status().isOk());
        verify(service).summary(1L, null, null, null, null);
        mvc.perform(get("/lending/v1/staff-notification-broadcasts").with(as("FINANCE")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Human Capital, Credit and SUPER_ADMIN opt a member out on their request, with a reason; Finance"
            + " reads it but cannot change it")
    void offerMessages() throws Exception {
        when(messages.set(eq("E1043"), any())).thenReturn(OPTED_OUT);
        when(messages.get("E1043")).thenReturn(OPTED_OUT);

        mvc.perform(put("/lending/v1/staff-members/E1043/offer-messages").with(as("FINANCE"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_OFFER_MESSAGES_REQUEST))
                .andExpect(status().isForbidden());
        for (String role : List.of("HUMAN_CAPITAL", "CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(put("/lending/v1/staff-members/E1043/offer-messages").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.STAFF_OFFER_MESSAGES_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Employee E1043 will no longer be sent offer messages;"
                            + " offers still appear in the app"))
                    .andExpect(jsonPath("$.data.optedOut").value(true));
        }
        mvc.perform(put("/lending/v1/staff-members/E1043/offer-messages").with(as("HUMAN_CAPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"optedOut\": true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reason").value("A reason is required"));
        mvc.perform(get("/lending/v1/staff-members/E1043/offer-messages").with(as("FINANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("Asked by phone"));
    }
}
