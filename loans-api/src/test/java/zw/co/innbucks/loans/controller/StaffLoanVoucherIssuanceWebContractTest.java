package zw.co.innbucks.loans.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.staff.loan.IssuanceInProgressException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceResult;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer;
import zw.co.innbucks.loans.core.staff.loan.UnredeemedVoucherTreatment;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GetMoreApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The voucher issuance screens on the real security chain: Credit, Finance and SUPER_ADMIN read the issuances, only
 * Credit and SUPER_ADMIN try one again and only with a reason; generateAgain reaches the issuer only when asked; each
 * refusal is its own status and code; and the message says where the issuance stands after the attempt.
 */
@SpringJUnitWebConfig(StaffLoanVoucherIssuanceWebContractTest.Config.class)
class StaffLoanVoucherIssuanceWebContractTest {

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, StaffLoanVoucherIssuanceController.class})
    static class Config {
    }

    private static final String RETRY = "/lending/v1/staff-loans/164/voucher-issuance";

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean StaffLoanVoucherIssuer issuer;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        for (String role : List.of("SUPER_ADMIN", "CREDIT_MANAGER", "FINANCE", "AGENTS", "HUMAN_CAPITAL",
                "VOUCHER_SUPPORT")) {
            when(jwtDecoder.decode(role + "-token")).thenReturn(Jwt.withTokenValue(role + "-token")
                    .header("alg", "HS256").subject(role).claim("preferred_username", role.toLowerCase())
                    .claim("realm_access", Map.of("roles", List.of(role)))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
        }
    }

    private static MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + role + "-token");
    }

    private static MockHttpServletRequestBuilder retry(String role, String body) {
        return as(role, post(RETRY)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static StaffLoanVoucherIssuanceResponse issuance(StaffLoanVoucherIssuanceStatus status, String lastOutcome,
                                                     Long voucherId) {
        return new StaffLoanVoucherIssuanceResponse(12L, 164L, "SGL-2026-000164", status, GetMoreMode.SIMULATED,
                "SIM-K7Q2M3XW-", 3, 2, 2, 0, lastOutcome, null, LocalDateTime.of(2026, 10, 14, 8, 24, 30), voucherId,
                LocalDateTime.of(2026, 10, 14, 8, 20, 5), LocalDateTime.of(2026, 10, 14, 8, 24, 2));
    }

    private static StaffLoanResponse loan(StaffLoanStatus status) {
        return new StaffLoanResponse(164L, "SGL-2026-000164", 41L, 9L, "E1064", "Rutendo Chari", "****0064", "C4",
                "getmore-groceries", "GetMore Groceries", new BigDecimal("150.00"), "USD", new BigDecimal("0.00"),
                new BigDecimal("150.00"), LocalDate.of(2026, 11, 20), UnredeemedVoucherTreatment.DEBT_STANDS,
                status, false, LocalDateTime.of(2026, 10, 14, 8, 20, 5),
                status == StaffLoanStatus.DISBURSED ? LocalDateTime.of(2026, 10, 14, 8, 24, 2) : null,
                status == StaffLoanStatus.DISBURSED ? "GETMORE-SIM-1207" : null, null, null, null, null, null,
                null);
    }

    @Test
    @DisplayName("Credit, Finance and SUPER_ADMIN read the issuances, of one status when asked; others are refused")
    void readers() throws Exception {
        when(issuer.issuances(any(), any())).thenReturn(new PageImpl<>(List.of(
                issuance(StaffLoanVoucherIssuanceStatus.PARKED, "GENERATED_NOT_FOUND", null))));
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "SUPER_ADMIN")) {
            mvc.perform(as(role, get("/lending/v1/staff-loan-voucher-issuances").param("status", "PARKED")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].loanReference").value("SGL-2026-000164"))
                    .andExpect(jsonPath("$.data.items[0].status").value("PARKED"))
                    .andExpect(jsonPath("$.data.items[0].lastOutcome").value("GENERATED_NOT_FOUND"))
                    .andExpect(jsonPath("$.data.items[0].mintedCodes").value(0))
                    .andExpect(jsonPath("$.data.items[0].couponPrefix").value("SIM-K7Q2M3XW-"));
        }
        verify(issuer, org.mockito.Mockito.times(3)).issuances(eq(StaffLoanVoucherIssuanceStatus.PARKED), any());

        mvc.perform(as("FINANCE", get("/lending/v1/staff-loan-voucher-issuances"))).andExpect(status().isOk());
        verify(issuer).issuances(isNull(), any());

        for (String role : List.of("AGENTS", "HUMAN_CAPITAL", "VOUCHER_SUPPORT")) {
            mvc.perform(as(role, get("/lending/v1/staff-loan-voucher-issuances")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/lending/v1/staff-loan-voucher-issuances")).andExpect(status().isUnauthorized());
        mvc.perform(as("FINANCE", get("/lending/v1/staff-loan-voucher-issuances").param("status", "LOST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Invalid value for 'status'"));
    }

    @Test
    @DisplayName("a retry: Credit or SUPER_ADMIN with a reason, answered with the issuance and the loan after it")
    void retried() throws Exception {
        when(issuer.retry(164L, "GetMore confirmed no coupon was made on the prefix", true)).thenReturn(
                new StaffLoanVoucherIssuanceResult(issuance(StaffLoanVoucherIssuanceStatus.ISSUED, "ISSUED", 9L),
                        loan(StaffLoanStatus.DISBURSED)));

        for (String role : List.of("CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(retry(role, GetMoreApiExamples.RETRY_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("The voucher of SGL-2026-000164 is issued"))
                    .andExpect(jsonPath("$.data.issuance.status").value("ISSUED"))
                    .andExpect(jsonPath("$.data.issuance.voucherId").value(9))
                    .andExpect(jsonPath("$.data.loan.status").value("DISBURSED"))
                    .andExpect(jsonPath("$.data.loan.msisdn").value("****0064"))
                    .andExpect(jsonPath("$.data.loan.disbursementReference").value("GETMORE-SIM-1207"));
        }
    }

    @Test
    @DisplayName("generateAgain reaches the issuer only when it is true; absent or false is a plain retry")
    void generateAgainOnlyWhenAsked() throws Exception {
        when(issuer.retry(anyLong(), anyString(), anyBoolean())).thenReturn(new StaffLoanVoucherIssuanceResult(
                issuance(StaffLoanVoucherIssuanceStatus.PENDING, "GETMORE_UNAVAILABLE", null),
                loan(StaffLoanStatus.AWAITING_DISBURSEMENT)));

        mvc.perform(retry("CREDIT_MANAGER", "{\"reason\": \"GetMore is back\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("The voucher of SGL-2026-000164 is not issued yet"
                        + " (GETMORE_UNAVAILABLE); it is tried again on the borrower's next read, or here"));
        verify(issuer).retry(164L, "GetMore is back", false);

        mvc.perform(retry("CREDIT_MANAGER", "{\"reason\": \"Tried again\", \"generateAgain\": false}"))
                .andExpect(status().isOk());
        verify(issuer).retry(164L, "Tried again", false);
    }

    @Test
    @DisplayName("no reason: 400 field by field, and nothing is tried; Finance and the rest may not try at all")
    void refusedBeforeTheIssuer() throws Exception {
        mvc.perform(retry("CREDIT_MANAGER", "{\"generateAgain\": true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.reason").value("reason is required"));
        mvc.perform(retry("CREDIT_MANAGER", "{\"reason\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reason").value("reason is required"));
        mvc.perform(retry("CREDIT_MANAGER", "{\"reason\": \"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reason").value("reason must be at most 255 characters"));
        for (String role : List.of("FINANCE", "AGENTS", "HUMAN_CAPITAL", "VOUCHER_SUPPORT")) {
            mvc.perform(retry(role, GetMoreApiExamples.RETRY_REQUEST)).andExpect(status().isForbidden());
        }
        mvc.perform(post(RETRY).contentType(MediaType.APPLICATION_JSON).content(GetMoreApiExamples.RETRY_REQUEST))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(issuer);
    }

    @Test
    @DisplayName("each refusal of the issuer is its own status and code")
    void refusals() throws Exception {
        when(issuer.retry(eq(164L), anyString(), anyBoolean()))
                .thenThrow(new ConflictException("Staff loan SGL-2026-000164 is DISBURSED; only a loan awaiting its"
                        + " voucher can be issued one"))
                .thenThrow(new ConflictException("Staff loan SGL-2026-000164 reached its payday cut-off (2026-11-20)"
                        + " without a voucher: cancel the loan"))
                .thenThrow(new IssuanceInProgressException("SGL-2026-000164"))
                .thenThrow(new GetMoreUnavailableException(null, GetMoreUnavailableException.Reason.OFF,
                        "GetMore is OFF on this host (GETMORE_MODE): no voucher can be issued"))
                .thenThrow(new NotFoundException("Staff loan 164 not found"));

        mvc.perform(retry("CREDIT_MANAGER", GetMoreApiExamples.RETRY_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Staff loan SGL-2026-000164 is DISBURSED; only a loan"
                        + " awaiting its voucher can be issued one"));
        mvc.perform(retry("CREDIT_MANAGER", GetMoreApiExamples.RETRY_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Staff loan SGL-2026-000164 reached its payday cut-off"
                        + " (2026-11-20) without a voucher: cancel the loan"));
        mvc.perform(retry("CREDIT_MANAGER", GetMoreApiExamples.RETRY_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOUCHER_ISSUANCE_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value("A voucher for SGL-2026-000164 is being issued right now;"
                        + " try again in a minute."));
        mvc.perform(retry("CREDIT_MANAGER", GetMoreApiExamples.RETRY_REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GETMORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("GetMore unavailable (OFF): GetMore is OFF on this host"
                        + " (GETMORE_MODE): no voucher can be issued"));
        mvc.perform(retry("CREDIT_MANAGER", GetMoreApiExamples.RETRY_REQUEST))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(issuer, never()).issuances(any(), any());
    }

    @Test
    @DisplayName("the message for every state an issuance can be left in")
    void messages() {
        assertThat(StaffLoanVoucherIssuanceController.message(
                issuance(StaffLoanVoucherIssuanceStatus.ISSUED, "ISSUED", 9L)))
                .isEqualTo("The voucher of SGL-2026-000164 is issued");
        assertThat(StaffLoanVoucherIssuanceController.message(
                issuance(StaffLoanVoucherIssuanceStatus.CALLING, "GETMORE_OUTCOME_UNKNOWN", null)))
                .isEqualTo("The voucher of SGL-2026-000164 is not issued yet (GETMORE_OUTCOME_UNKNOWN); it is tried"
                        + " again on the borrower's next read, or here");
        assertThat(StaffLoanVoucherIssuanceController.message(
                issuance(StaffLoanVoucherIssuanceStatus.PARKED, "PREFIX_NOT_HONOURED", null)))
                .isEqualTo("The voucher of SGL-2026-000164 is parked (PREFIX_NOT_HONOURED)");
        assertThat(StaffLoanVoucherIssuanceController.message(
                issuance(StaffLoanVoucherIssuanceStatus.CANCELLING, "GETMORE_UNAVAILABLE", null)))
                .isEqualTo("The voucher of SGL-2026-000164 is being withdrawn at GetMore (GETMORE_UNAVAILABLE)");
        assertThat(StaffLoanVoucherIssuanceController.message(
                issuance(StaffLoanVoucherIssuanceStatus.ABANDONED, "CANCELLED", null)))
                .isEqualTo("The voucher of SGL-2026-000164 is withdrawn at GetMore: the loan was cancelled");
    }
}
