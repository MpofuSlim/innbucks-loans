package zw.co.innbucks.loans.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
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
import zw.co.innbucks.loans.core.borrower.AssertionRejectedException;
import zw.co.innbucks.loans.core.instrument.SigningContext;
import zw.co.innbucks.loans.core.staff.loan.AcceptStaffLoanRequest;
import zw.co.innbucks.loans.core.staff.loan.DrawRules;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanAppliedOffer;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanDecline;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanDeclinedException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanHome;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanJourneyService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanOfferView;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanRequestInvalidException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanTermsChangedException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanTermsUnavailableException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanView;
import zw.co.innbucks.loans.core.staff.loan.StepUpRequiredException;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;
import zw.co.innbucks.loans.web.StaffLoanApiExamples;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Staff Grocery Loan endpoints on the real security chain: the journey answers borrower sessions only, for the
 * staff member the session names, with each refusal its own status and code; the portal screens answer their roles.
 */
@SpringJUnitWebConfig(StaffLoanWebContractTest.Config.class)
class StaffLoanWebContractTest {

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, BorrowerStaffLoanController.class,
            StaffLoanController.class})
    static class Config {
    }

    private static final String JOURNEY = "/lending/v1/borrower/staff-grocery-loan";
    private static final StaffLoanOfferView OFFER = new StaffLoanOfferView(31L, new BigDecimal("300.00"), "USD",
            LocalDateTime.of(2026, 10, 5, 6, 0), new DrawRules(new BigDecimal("10.00"), new BigDecimal("5.00"),
            new BigDecimal("300.00"), "USD"));
    private static final StaffLoanView LOAN = new StaffLoanView("SGL-2026-000143",
            StaffLoanStatus.AWAITING_DISBURSEMENT, "Your loan is approved.", new BigDecimal("300.00"), "USD",
            new BigDecimal("300.00"), new BigDecimal("300.00"), LocalDate.of(2026, 11, 20), "GetMore Groceries",
            LocalDateTime.of(2026, 10, 1, 7, 10, 41), null);

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean StaffLoanJourneyService journeyService;
    @MockitoBean StaffLoanService loanService;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        Jwt borrower = Jwt.withTokenValue("borrower-token").header("alg", "HS256")
                .subject("staff-member:2")
                .claim("preferred_username", "borrower:E1012")
                .claim("realm_access", Map.of("roles", List.of("BORROWER")))
                .claim(JwtService.TOKEN_USE_CLAIM, JwtService.BORROWER_TOKEN_USE)
                .claim(JwtService.STAFF_MEMBER_CLAIM, 2L)
                .claim(JwtService.MSISDN_CLAIM, "263773456789")
                .claim(JwtService.AUTHENTICATION_METHODS_CLAIM, List.of("pin"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900))
                .build();
        when(jwtDecoder.decode("borrower-token")).thenReturn(borrower);
        for (String role : List.of("CREDIT_MANAGER", "HUMAN_CAPITAL", "AGENTS")) {
            when(jwtDecoder.decode(role + "-token")).thenReturn(Jwt.withTokenValue(role + "-token")
                    .header("alg", "HS256").subject(role).claim("preferred_username", role.toLowerCase())
                    .claim("realm_access", Map.of("roles", List.of(role)))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
        }
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token + "-token");
    }

    private static MockHttpServletRequestBuilder accept() {
        return as("borrower", post(JOURNEY + "/loans")).contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Id", "a1f3c9e2-7b4d").content(StaffLoanApiExamples.ACCEPT_REQUEST);
    }

    @Test
    @DisplayName("the tile is the session's own staff member's")
    void home() throws Exception {
        when(journeyService.home(2L)).thenReturn(new StaffLoanHome(null, OFFER, false, null));

        mvc.perform(as("borrower", get(JOURNEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.offer.offerId").value(31))
                .andExpect(jsonPath("$.data.offer.draw.increment").value(5.00))
                .andExpect(jsonPath("$.data.canApply").value(false))
                .andExpect(jsonPath("$.data.loan").isEmpty());
        verify(journeyService).home(2L);
    }

    @Test
    @DisplayName("apply: 201 for an offer made now, 200 for the one already held, 422 with the reason when declined")
    void apply() throws Exception {
        when(journeyService.apply(2L)).thenReturn(new StaffLoanAppliedOffer(OFFER, true));
        mvc.perform(as("borrower", post(JOURNEY + "/offers")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.data.created").value(true));

        when(journeyService.apply(2L)).thenReturn(new StaffLoanAppliedOffer(OFFER, false));
        mvc.perform(as("borrower", post(JOURNEY + "/offers"))).andExpect(status().isOk());

        when(journeyService.apply(2L)).thenThrow(new StaffLoanDeclinedException(StaffLoanDecline.HAS_ACTIVE_LOAN));
        mvc.perform(as("borrower", post(JOURNEY + "/offers")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("STAFF_LOAN_DECLINED"))
                .andExpect(jsonPath("$.message").value(StaffLoanDecline.HAS_ACTIVE_LOAN.message()))
                .andExpect(jsonPath("$.data.reason").value("HAS_ACTIVE_LOAN"));
    }

    @Test
    @DisplayName("quote: a missing amount is a field error before the service; a refused amount, the service's own")
    void quote() throws Exception {
        mvc.perform(as("borrower", post(JOURNEY + "/quote")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\": 31}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.amount").value("amount is required"));
        verifyNoInteractions(journeyService);

        when(journeyService.quote(eq(2L), any())).thenThrow(new StaffLoanRequestInvalidException("amount",
                "Choose an amount from USD 10.00 to USD 300.00 in steps of USD 5.00"));
        mvc.perform(as("borrower", post(JOURNEY + "/quote")).contentType(MediaType.APPLICATION_JSON)
                        .content(StaffLoanApiExamples.QUOTE_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.data.amount")
                        .value("Choose an amount from USD 10.00 to USD 300.00 in steps of USD 5.00"));
    }

    @Test
    @DisplayName("accept: 201 with the loan; the device, address and agent are handed over as evidence")
    void accept201() throws Exception {
        when(journeyService.accept(eq(2L), any(), any())).thenReturn(LOAN);

        mvc.perform(accept().header("X-Forwarded-For", "196.27.112.45").header("User-Agent", "InnBucks/2.4.1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reference").value("SGL-2026-000143"))
                .andExpect(jsonPath("$.data.status").value("AWAITING_DISBURSEMENT"));

        ArgumentCaptor<AcceptStaffLoanRequest> request = ArgumentCaptor.forClass(AcceptStaffLoanRequest.class);
        ArgumentCaptor<SigningContext> signing = ArgumentCaptor.forClass(SigningContext.class);
        verify(journeyService).accept(eq(2L), request.capture(), signing.capture());
        assertThat(request.getValue().agreementSha256()).isEqualTo(StaffLoanApiExamples.AGREEMENT_SHA256);
        assertThat(request.getValue().toString()).doesNotContain("eyJ");
        assertThat(signing.getValue().deviceId()).isEqualTo("a1f3c9e2-7b4d");
        assertThat(signing.getValue().forwardedFor()).isEqualTo("196.27.112.45");
        assertThat(signing.getValue().userAgent()).isEqualTo("InnBucks/2.4.1");
    }

    @Test
    @DisplayName("accept: each refusal is its own status and code")
    void acceptRefusals() throws Exception {
        doThrow(new StepUpRequiredException()).when(journeyService).accept(anyLong(), any(), any());
        mvc.perform(accept()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));

        doThrow(new AssertionRejectedException("replayed jti")).when(journeyService).accept(anyLong(), any(), any());
        mvc.perform(accept()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ASSERTION_REJECTED"));

        doThrow(new StaffLoanTermsChangedException()).when(journeyService).accept(anyLong(), any(), any());
        mvc.perform(accept()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMS_CHANGED"));

        doThrow(new StaffLoanTermsUnavailableException()).when(journeyService).accept(anyLong(), any(), any());
        mvc.perform(accept()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STAFF_LOAN_TERMS_UNAVAILABLE"));

        doThrow(new StaffLoanDeclinedException(StaffLoanDecline.OFFER_NOT_AVAILABLE)).when(journeyService)
                .accept(anyLong(), any(), any());
        mvc.perform(accept()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.data.reason").value("OFFER_NOT_AVAILABLE"));
    }

    @Test
    @DisplayName("an accept without the agreement fingerprint or the assertion is refused before the service")
    void acceptValidation() throws Exception {
        mvc.perform(as("borrower", post(JOURNEY + "/loans")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\": 31, \"amount\": 300, \"agreementVersion\": 1,"
                                + " \"agreementSha256\": \"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.agreementSha256").exists())
                .andExpect(jsonPath("$.data.assertion").value("assertion is required"));
        verifyNoInteractions(journeyService);
    }

    @Test
    @DisplayName("the journey answers borrowers only; the portal screens answer staff only, by role")
    void sessionsAndRoles() throws Exception {
        mvc.perform(as("CREDIT_MANAGER", get(JOURNEY))).andExpect(status().isForbidden());
        mvc.perform(get(JOURNEY)).andExpect(status().isUnauthorized());
        mvc.perform(as("borrower", get("/lending/v1/staff-loans"))).andExpect(status().isForbidden());
        mvc.perform(as("AGENTS", get("/lending/v1/staff-loans"))).andExpect(status().isForbidden());

        when(loanService.loans(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        mvc.perform(as("HUMAN_CAPITAL", get("/lending/v1/staff-loans").param("status", "AWAITING_DISBURSEMENT")))
                .andExpect(status().isOk());
        verify(loanService).loans(eq(StaffLoanStatus.AWAITING_DISBURSEMENT), any(), any());
        mvc.perform(as("HUMAN_CAPITAL", post("/lending/v1/staff-loans/143/cancel"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffLoanApiExamples.CANCEL_REQUEST))
                .andExpect(status().isForbidden());
        verifyNoInteractions(journeyService);
    }

    @Test
    @DisplayName("Credit cancels with a reason; without one it is a field error")
    void cancel() throws Exception {
        mvc.perform(as("CREDIT_MANAGER", post("/lending/v1/staff-loans/143/cancel"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reason").value("reason is required"));

        mvc.perform(as("CREDIT_MANAGER", post("/lending/v1/staff-loans/143/cancel"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffLoanApiExamples.CANCEL_REQUEST))
                .andExpect(status().isOk());
        verify(loanService).cancel(143L, "Accepted in error: the borrower asked to cancel before payout");
    }
}
