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
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreException;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.instrument.SigningContext;
import zw.co.innbucks.loans.core.staff.loan.AcceptStaffLoanRequest;
import zw.co.innbucks.loans.core.staff.loan.DrawRules;
import zw.co.innbucks.loans.core.staff.loan.IssuanceInProgressException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanAcceptance;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCancellation;
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
import zw.co.innbucks.loans.core.voucher.BorrowerVoucherResponse;
import zw.co.innbucks.loans.core.voucher.BorrowerVoucherSpendsResponse;
import zw.co.innbucks.loans.core.voucher.GetMoreDispatcher;
import zw.co.innbucks.loans.core.voucher.VoucherCodeSource;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GetMoreApiExamples;
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
import static org.mockito.ArgumentMatchers.isNull;
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
            LocalDateTime.of(2026, 10, 1, 7, 10, 41), null, null);

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean StaffLoanJourneyService journeyService;
    @MockitoBean StaffLoanService loanService;
    @MockitoBean StaffLoanAcceptance acceptance;
    @MockitoBean StaffLoanCancellation cancellation;
    @MockitoBean GetMoreDispatcher getMoreDispatcher;
    @MockitoBean VoucherService voucherService;

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
        for (String role : List.of("CREDIT_MANAGER", "HUMAN_CAPITAL", "FINANCE", "AGENTS")) {
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
    @DisplayName("their loans are the session's own staff member's, paged; staff and anonymous callers are refused")
    void history() throws Exception {
        StaffLoanView repaid = new StaffLoanView("SGL-2026-000143", StaffLoanStatus.REPAID, "Repaid in full. Thank you.",
                new BigDecimal("300.00"), "USD", new BigDecimal("300.00"), new BigDecimal("0.00"),
                LocalDate.of(2026, 11, 20), "GetMore Groceries", LocalDateTime.of(2026, 10, 6, 7, 10, 41), null,
                null);
        when(journeyService.loans(eq(2L), any())).thenReturn(new PageImpl<>(List.of(repaid),
                org.springframework.data.domain.PageRequest.of(1, 5), 6));

        mvc.perform(as("borrower", get(JOURNEY + "/loans").param("page", "1").param("size", "5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].reference").value("SGL-2026-000143"))
                .andExpect(jsonPath("$.data.items[0].status").value("REPAID"))
                .andExpect(jsonPath("$.data.items[0].outstandingBalance").value(0.00))
                .andExpect(jsonPath("$.data.totalItems").value(6))
                .andExpect(jsonPath("$.data.page").value(1));
        verify(journeyService).loans(eq(2L), org.mockito.ArgumentMatchers.argThat(paging ->
                paging.getPageNumber() == 1 && paging.getPageSize() == 5));
        mvc.perform(as("HUMAN_CAPITAL", get(JOURNEY + "/loans"))).andExpect(status().isForbidden());
        mvc.perform(get(JOURNEY + "/loans")).andExpect(status().isUnauthorized());
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
        when(acceptance.accept(eq(2L), any(), any())).thenReturn(LOAN);

        mvc.perform(accept().header("X-Forwarded-For", "196.27.112.45").header("User-Agent", "InnBucks/2.4.1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reference").value("SGL-2026-000143"))
                .andExpect(jsonPath("$.data.status").value("AWAITING_DISBURSEMENT"))
                .andExpect(jsonPath("$.data.voucherIssuance").doesNotExist());

        ArgumentCaptor<AcceptStaffLoanRequest> request = ArgumentCaptor.forClass(AcceptStaffLoanRequest.class);
        ArgumentCaptor<SigningContext> signing = ArgumentCaptor.forClass(SigningContext.class);
        verify(acceptance).accept(eq(2L), request.capture(), signing.capture());
        verifyNoInteractions(journeyService);
        assertThat(request.getValue().agreementSha256()).isEqualTo(StaffLoanApiExamples.AGREEMENT_SHA256);
        assertThat(request.getValue().toString()).doesNotContain("eyJ");
        assertThat(signing.getValue().deviceId()).isEqualTo("a1f3c9e2-7b4d");
        assertThat(signing.getValue().forwardedFor()).isEqualTo("196.27.112.45");
        assertThat(signing.getValue().userAgent()).isEqualTo("InnBucks/2.4.1");
    }

    @Test
    @DisplayName("accept: each refusal is its own status and code")
    void acceptRefusals() throws Exception {
        doThrow(new StepUpRequiredException()).when(acceptance).accept(anyLong(), any(), any());
        mvc.perform(accept()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"));

        doThrow(new AssertionRejectedException("replayed jti")).when(acceptance).accept(anyLong(), any(), any());
        mvc.perform(accept()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ASSERTION_REJECTED"));

        doThrow(new StaffLoanTermsChangedException()).when(acceptance).accept(anyLong(), any(), any());
        mvc.perform(accept()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMS_CHANGED"));

        doThrow(new StaffLoanTermsUnavailableException()).when(acceptance).accept(anyLong(), any(), any());
        mvc.perform(accept()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STAFF_LOAN_TERMS_UNAVAILABLE"));

        doThrow(new StaffLoanDeclinedException(StaffLoanDecline.OFFER_NOT_AVAILABLE)).when(acceptance)
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
        verifyNoInteractions(journeyService, acceptance);
    }

    @Test
    @DisplayName("the journey answers borrowers only; the portal screens answer staff only, by role")
    void sessionsAndRoles() throws Exception {
        mvc.perform(as("CREDIT_MANAGER", get(JOURNEY))).andExpect(status().isForbidden());
        mvc.perform(get(JOURNEY)).andExpect(status().isUnauthorized());
        mvc.perform(as("borrower", get("/lending/v1/staff-loans"))).andExpect(status().isForbidden());
        mvc.perform(as("AGENTS", get("/lending/v1/staff-loans"))).andExpect(status().isForbidden());

        when(loanService.loans(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        mvc.perform(as("HUMAN_CAPITAL", get("/lending/v1/staff-loans").param("status", "AWAITING_DISBURSEMENT")))
                .andExpect(status().isOk());
        verify(loanService).loans(eq(StaffLoanStatus.AWAITING_DISBURSEMENT), any(), isNull(), any());
        mvc.perform(as("FINANCE", get("/lending/v1/staff-loans").param("flagged", "true")))
                .andExpect(status().isOk());
        verify(loanService).loans(isNull(), isNull(), eq(true), any());
        mvc.perform(as("FINANCE", get("/lending/v1/staff-loans").param("flagged", "maybe")))
                .andExpect(status().isBadRequest());
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

        when(cancellation.cancel(eq(143L), any())).thenReturn(new StaffLoanCancellation.Result(null, "Cancelled"));
        mvc.perform(as("CREDIT_MANAGER", post("/lending/v1/staff-loans/143/cancel"))
                        .contentType(MediaType.APPLICATION_JSON).content(StaffLoanApiExamples.CANCEL_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cancelled"));
        verify(cancellation).cancel(143L, "Accepted in error: the borrower asked to cancel before payout");
        verifyNoInteractions(loanService);
    }

    @Test
    @DisplayName("cancelling a GetMore loan: the message says what became of the coupon; each refusal its own code")
    void cancelGetMore() throws Exception {
        when(cancellation.cancel(eq(164L), any())).thenReturn(new StaffLoanCancellation.Result(null,
                "Cancelled. The voucher's coupon is withdrawn at GetMore."));
        mvc.perform(cancel164()).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cancelled. The voucher's coupon is withdrawn at GetMore."));

        doThrow(new IssuanceInProgressException("SGL-2026-000164")).when(cancellation).cancel(eq(164L), any());
        mvc.perform(cancel164()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOUCHER_ISSUANCE_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value("A voucher for SGL-2026-000164 is being issued right now; try"
                        + " again in a minute."));

        doThrow(new ConflictException("The voucher of SGL-2026-000164 has been used (USD 45.50 spent, USD 0.00 held at"
                + " the till); a loan whose voucher was used cannot be cancelled.")).when(cancellation)
                .cancel(eq(164L), any());
        mvc.perform(cancel164()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        doThrow(new GetMoreRejectedException(GetMoreException.Operation.DELETE_COUPONS,
                GetMoreRejectedException.Reason.INVALID, "GetMore did not withdraw the coupon of voucher 9 (****8B1C);"
                + " nothing was changed in loans")).when(cancellation).cancel(eq(164L), any());
        mvc.perform(cancel164()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GETMORE_REJECTED"));

        doThrow(new GetMoreOutcomeUnknownException(GetMoreException.Operation.DELETE_COUPONS, "the answer was lost"))
                .when(cancellation).cancel(eq(164L), any());
        mvc.perform(cancel164()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GETMORE_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.endsWith(
                        "Repeat the request: it is safe")));

        doThrow(new GetMoreUnavailableException(null, GetMoreUnavailableException.Reason.READ_FAILED,
                "Cannot read voucher 9 at GetMore right now; try again.")).when(cancellation).cancel(eq(164L), any());
        mvc.perform(cancel164()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GETMORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("GetMore unavailable (READ_FAILED): Cannot read voucher 9 at"
                        + " GetMore right now; try again."));
    }

    private static MockHttpServletRequestBuilder cancel164() {
        return as("CREDIT_MANAGER", post("/lending/v1/staff-loans/164/cancel"))
                .contentType(MediaType.APPLICATION_JSON).content(StaffLoanApiExamples.CANCEL_REQUEST);
    }

    @Test
    @DisplayName("accept for a GetMore store: 201 DISBURSED with the voucher and its code as GetMore made it")
    void acceptWithVoucher() throws Exception {
        BorrowerVoucherResponse voucher = new BorrowerVoucherResponse(VoucherStatus.ISSUED, new BigDecimal("150.00"),
                new BigDecimal("150.00"), "USD", LocalDateTime.of(2026, 11, 13, 21, 59, 59), "****8B1C",
                GetMoreApiExamples.CODE_9, GetMoreApiExamples.CODE_9, VoucherCodeSource.GETMORE, true,
                new BigDecimal("0.00"), true, LocalDateTime.of(2026, 10, 14, 8, 20, 7));
        when(acceptance.accept(eq(2L), any(), any())).thenReturn(new StaffLoanView("SGL-2026-000164",
                StaffLoanStatus.DISBURSED, "Your voucher is ready to spend at GetMore Groceries.",
                new BigDecimal("150.00"), "USD", new BigDecimal("150.00"), new BigDecimal("150.00"),
                LocalDate.of(2026, 11, 20), "GetMore Groceries", LocalDateTime.of(2026, 10, 14, 8, 20, 5), voucher,
                null));

        mvc.perform(accept())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DISBURSED"))
                .andExpect(jsonPath("$.data.voucher.code").value("SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C"))
                .andExpect(jsonPath("$.data.voucher.scanValue").value("SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C"))
                .andExpect(jsonPath("$.data.voucher.store").value("GETMORE"))
                .andExpect(jsonPath("$.data.voucher.simulated").value(true))
                .andExpect(jsonPath("$.data.voucherIssuance").doesNotExist());

        when(acceptance.accept(eq(2L), any(), any())).thenReturn(new StaffLoanView("SGL-2026-000164",
                StaffLoanStatus.AWAITING_DISBURSEMENT, "Your loan is approved. Your voucher is being prepared and will"
                + " appear here shortly.", new BigDecimal("150.00"), "USD", new BigDecimal("150.00"),
                new BigDecimal("150.00"), LocalDate.of(2026, 11, 20), "GetMore Groceries",
                LocalDateTime.of(2026, 10, 14, 8, 20, 5), null, StaffLoanView.VoucherIssuance.PENDING));
        mvc.perform(accept())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("AWAITING_DISBURSEMENT"))
                .andExpect(jsonPath("$.data.voucher").isEmpty())
                .andExpect(jsonPath("$.data.voucherIssuance").value("PENDING"));
    }

    @Test
    @DisplayName("each read of the tile, the loans and the spends brings the borrower's GetMore vouchers up to date")
    void readsRefresh() throws Exception {
        when(journeyService.home(2L)).thenReturn(new StaffLoanHome(null, null, true, null));
        when(journeyService.loans(eq(2L), any())).thenReturn(new PageImpl<>(List.of()));
        when(voucherService.spendsFor(2L, "SGL-2026-000164")).thenReturn(null);

        mvc.perform(as("borrower", get(JOURNEY))).andExpect(status().isOk());
        mvc.perform(as("borrower", get(JOURNEY + "/loans"))).andExpect(status().isOk());
        mvc.perform(as("borrower", get(JOURNEY + "/loans/SGL-2026-000164/voucher-spends"))).andExpect(status().isOk());

        verify(getMoreDispatcher, org.mockito.Mockito.times(3)).refresh(2L);
    }

    @Test
    @DisplayName("voucher spends: the borrower's own loan, oldest first; another's (or none yet) is a 404")
    void voucherSpends() throws Exception {
        when(voucherService.spendsFor(2L, "SGL-2026-000164")).thenReturn(new BorrowerVoucherSpendsResponse(
                "SGL-2026-000164", new BigDecimal("150.00"), new BigDecimal("90.00"), new BigDecimal("0.00"),
                new BigDecimal("60.00"), "USD", LocalDateTime.of(2026, 10, 14, 8, 40, 3), true, true, List.of(
                new BorrowerVoucherSpendsResponse.Movement(BorrowerVoucherSpendsResponse.Kind.SPEND,
                        new BigDecimal("45.50"), new BigDecimal("104.50"), LocalDateTime.of(2026, 10, 14, 8, 31, 2),
                        "GetMore Avondale", "SIM000000012"),
                new BorrowerVoucherSpendsResponse.Movement(BorrowerVoucherSpendsResponse.Kind.REFUND,
                        new BigDecimal("10.00"), new BigDecimal("60.00"), LocalDateTime.of(2026, 10, 14, 8, 39, 58),
                        "GetMore Avondale", "SIM000000012"))));
        when(voucherService.spendsFor(2L, "SGL-2026-000151")).thenThrow(new NotFoundException(
                "Loan SGL-2026-000151 has no voucher yet"));

        mvc.perform(as("borrower", get(JOURNEY + "/loans/SGL-2026-000164/voucher-spends")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(60.00))
                .andExpect(jsonPath("$.data.movements[0].kind").value("SPEND"))
                .andExpect(jsonPath("$.data.movements[1].kind").value("REFUND"))
                .andExpect(jsonPath("$.data.movements[1].orderNumber").value("SIM000000012"));
        mvc.perform(as("borrower", get(JOURNEY + "/loans/SGL-2026-000151/voucher-spends")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Loan SGL-2026-000151 has no voucher yet"));
        mvc.perform(as("CREDIT_MANAGER", get(JOURNEY + "/loans/SGL-2026-000164/voucher-spends")))
                .andExpect(status().isForbidden());
    }
}
