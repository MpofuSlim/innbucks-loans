package zw.co.innbucks.loans.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.staff.notification.BorrowerOfferMessages;
import zw.co.innbucks.loans.core.staff.notification.StaffInboxNotification;
import zw.co.innbucks.loans.core.staff.notification.StaffInboxReadAll;
import zw.co.innbucks.loans.core.staff.notification.StaffInboxService;
import zw.co.innbucks.loans.core.staff.notification.StaffInboxUnread;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationTemplate;
import zw.co.innbucks.loans.core.staff.notification.StaffOfferMessagesService;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.BorrowerApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SuperApp inbox and offer-message choice on the real security chain: borrower sessions only, always for the
 * staff member the session names; a portal session is refused.
 */
@SpringJUnitWebConfig(BorrowerNotificationWebContractTest.Config.class)
class BorrowerNotificationWebContractTest {

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, BorrowerNotificationController.class})
    static class Config {
    }

    private static final String BORROWER = "/lending/v1/borrower";
    private static final StaffInboxNotification OFFER_31 = new StaffInboxNotification(1103L,
            StaffNotificationTemplate.OFFER_NEW, "Your Staff Grocery Loan offer", "You have a new offer",
            LocalDateTime.of(2026, 9, 28, 6, 0, 1), null, 31L, true);
    private static final StaffInboxNotification LAUNCH = new StaffInboxNotification(1388L,
            StaffNotificationTemplate.LAUNCH, "Introducing the Staff Grocery Loan", "InnBucks has launched",
            LocalDateTime.of(2026, 10, 2, 7, 15, 4), LocalDateTime.of(2026, 10, 2, 8, 0), null, null);

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean StaffInboxService inboxService;
    @MockitoBean StaffOfferMessagesService offerMessagesService;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        when(jwtDecoder.decode("borrower-token")).thenReturn(Jwt.withTokenValue("borrower-token")
                .header("alg", "HS256").subject("staff-member:2")
                .claim("preferred_username", "borrower:E1012")
                .claim("realm_access", Map.of("roles", List.of("BORROWER")))
                .claim(JwtService.TOKEN_USE_CLAIM, JwtService.BORROWER_TOKEN_USE)
                .claim(JwtService.STAFF_MEMBER_CLAIM, 2L)
                .claim(JwtService.MSISDN_CLAIM, "263773456789")
                .claim(JwtService.AUTHENTICATION_METHODS_CLAIM, List.of("pin"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());
        when(jwtDecoder.decode("HUMAN_CAPITAL-token")).thenReturn(Jwt.withTokenValue("HUMAN_CAPITAL-token")
                .header("alg", "HS256").subject("hc1").claim("preferred_username", "hc1")
                .claim("realm_access", Map.of("roles", List.of("HUMAN_CAPITAL")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token + "-token");
    }

    @Test
    @DisplayName("the inbox is the session's own member's, newest first, paged; the offer one says if it is open")
    void inbox() throws Exception {
        when(inboxService.inbox(eq(2L), any())).thenReturn(new PageImpl<>(List.of(LAUNCH, OFFER_31),
                PageRequest.of(1, 2), 5));

        mvc.perform(as("borrower", get(BORROWER + "/notifications").param("page", "1").param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].kind").value("LAUNCH"))
                .andExpect(jsonPath("$.data.items[0].readAt").exists())
                .andExpect(jsonPath("$.data.items[0].offerOpen").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].offerId").value(31))
                .andExpect(jsonPath("$.data.items[1].offerOpen").value(true))
                .andExpect(jsonPath("$.data.items[1].readAt").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalItems").value(5));
        verify(inboxService).inbox(2L, PageRequest.of(1, 2));
    }

    @Test
    @DisplayName("the badge, marking one read, another's not found, and marking all read")
    void reading() throws Exception {
        when(inboxService.unread(2L)).thenReturn(new StaffInboxUnread(1));
        when(inboxService.read(2L, 1103L)).thenReturn(OFFER_31);
        when(inboxService.read(2L, 999L)).thenThrow(new NotFoundException("Notification 999 not found"));
        when(inboxService.readAll(2L)).thenReturn(new StaffInboxReadAll(1));

        mvc.perform(as("borrower", get(BORROWER + "/notifications/unread-count")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread").value(1));
        mvc.perform(as("borrower", post(BORROWER + "/notifications/1103/read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Marked read"))
                .andExpect(jsonPath("$.data.id").value(1103));
        mvc.perform(as("borrower", post(BORROWER + "/notifications/999/read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Notification 999 not found"));
        mvc.perform(as("borrower", post(BORROWER + "/notifications/read-all")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("1 notification marked read"))
                .andExpect(jsonPath("$.data.marked").value(1));
    }

    @Test
    @DisplayName("offer messages: the member's own choice, read and changed; optedOut is required")
    void offerMessages() throws Exception {
        when(offerMessagesService.forMember(2L)).thenReturn(new BorrowerOfferMessages(false, null));
        when(offerMessagesService.chooseForMember(2L, true)).thenReturn(new BorrowerOfferMessages(true,
                LocalDateTime.of(2026, 10, 1, 7, 3, 12)));
        when(offerMessagesService.chooseForMember(2L, false)).thenReturn(new BorrowerOfferMessages(false,
                LocalDateTime.of(2026, 10, 1, 7, 4, 0)));

        mvc.perform(as("borrower", get(BORROWER + "/offer-messages")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.optedOut").value(false))
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());
        mvc.perform(as("borrower", put(BORROWER + "/offer-messages")).contentType(MediaType.APPLICATION_JSON)
                        .content(BorrowerApiExamples.OFFER_MESSAGES_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Offer messages stopped; offers still appear in the app"))
                .andExpect(jsonPath("$.data.optedOut").value(true))
                .andExpect(jsonPath("$.data.updatedAt").exists());
        mvc.perform(as("borrower", put(BORROWER + "/offer-messages")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optedOut\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Offer messages restarted"));
        mvc.perform(as("borrower", put(BORROWER + "/offer-messages")).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.optedOut").value("optedOut is required"));
    }

    @Test
    @DisplayName("a portal session and no session are refused before anything is read")
    void onlyBorrowers() throws Exception {
        mvc.perform(as("HUMAN_CAPITAL", get(BORROWER + "/notifications"))).andExpect(status().isForbidden());
        mvc.perform(as("HUMAN_CAPITAL", put(BORROWER + "/offer-messages")).contentType(MediaType.APPLICATION_JSON)
                .content(BorrowerApiExamples.OFFER_MESSAGES_REQUEST)).andExpect(status().isForbidden());
        mvc.perform(get(BORROWER + "/notifications/unread-count")).andExpect(status().isUnauthorized());
        verifyNoInteractions(inboxService, offerMessagesService);
    }
}
