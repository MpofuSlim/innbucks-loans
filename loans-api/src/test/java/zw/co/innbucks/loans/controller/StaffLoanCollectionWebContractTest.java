package zw.co.innbucks.loans.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.staff.loan.FinaliseCollectionRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanOwed;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;
import zw.co.innbucks.loans.core.staff.loan.UnredeemedVoucherTreatment;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.core.voucher.VoucherStopReason;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The payday statement endpoints on the real security chain: Finance, Credit and SUPER_ADMIN read it and run the
 * payday sync, only Finance and SUPER_ADMIN finalise; the JSON says {@code final}, the CSV is named for the payday,
 * the simulated store's answers say they are not for payroll, and each refusal is its own status and code.
 */
@SpringJUnitWebConfig(StaffLoanCollectionWebContractTest.Config.class)
class StaffLoanCollectionWebContractTest {

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, StaffLoanCollectionController.class})
    static class Config {
    }

    private static final String STATEMENT = "/lending/v1/staff-loan-collections/2026-11-20";
    private static final LocalDate PAYDAY = LocalDate.of(2026, 11, 20);

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean StaffLoanCollectionService service;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        for (String role : List.of("SUPER_ADMIN", "CREDIT_MANAGER", "FINANCE", "AGENTS", "HUMAN_CAPITAL",
                "VOUCHER_SUPPORT", "MERCHANT_TILL")) {
            when(jwtDecoder.decode(role + "-token")).thenReturn(Jwt.withTokenValue(role + "-token")
                    .header("alg", "HS256").subject(role).claim("preferred_username", role.toLowerCase())
                    .claim("realm_access", Map.of("roles", List.of(role)))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
        }
    }

    private static MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + role + "-token");
    }

    static StaffLoanCollectionReport report(boolean simulated, boolean finalised) {
        StaffLoanCollectionReport.Line line = new StaffLoanCollectionReport.Line(164L, "SGL-2026-000164", "E1064",
                "Rutendo Chari", StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS, 9L,
                VoucherStatus.EXPIRED, VoucherStopReason.SIMULATOR_EXPIRED, "USD", new BigDecimal("150.00"),
                new BigDecimal("90.00"), new BigDecimal("10.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("150.00"), null, true, List.<StaffLoanOwed.Flag>of(),
                LocalDateTime.of(2026, 11, 20, 6, 2, 11), simulated);
        StaffLoanCollectionReport.MerchantSettlement settlement = new StaffLoanCollectionReport.MerchantSettlement(
                "getmore-groceries", "GetMore Groceries", LocalDateTime.of(2026, 11, 19, 22, 0), null, 0,
                List.of(new StaffLoanCollectionReport.SettlementTotals("USD", new BigDecimal("100.00"),
                        new BigDecimal("10.00"), new BigDecimal("90.00"))),
                List.of(new StaffLoanCollectionReport.SettlementLine(9L, "SGL-2026-000164", "USD",
                        new BigDecimal("100.00"), new BigDecimal("10.00"), new BigDecimal("90.00"), false)));
        return new StaffLoanCollectionReport(PAYDAY, LocalDateTime.of(2026, 11, 19, 22, 0),
                simulated ? GetMoreMode.SIMULATED : GetMoreMode.LIVE, simulated, finalised,
                finalised ? LocalDateTime.of(2026, 11, 20, 6, 5, 30) : null, finalised ? "finance1" : null, null,
                List.of(line), List.of(new StaffLoanCollectionReport.Totals("USD", 1, new BigDecimal("150.00"), 0,
                new BigDecimal("0.00"))), settlement, List.of(), List.of(), 0, 0);
    }

    @Test
    @DisplayName("Finance, Credit and SUPER_ADMIN read the statement; others are refused, and no token is a 401")
    void readers() throws Exception {
        when(service.report(PAYDAY)).thenReturn(report(true, false));
        for (String role : List.of("FINANCE", "CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(as(role, get(STATEMENT)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.message").value("SIMULATED: not for payroll"))
                    .andExpect(jsonPath("$.data.simulated").value(true))
                    .andExpect(jsonPath("$.data.lines[0].final").value(true))
                    .andExpect(jsonPath("$.data.lines[0].finalAmount").doesNotExist())
                    .andExpect(jsonPath("$.data.lines[0].amountToCollect").value(150.00))
                    .andExpect(jsonPath("$.data.lines[0].couldRiseTo").isEmpty())
                    .andExpect(jsonPath("$.data.merchantSettlement.totals[0].net").value(90.00))
                    .andExpect(jsonPath("$.data.finalisedAt").isEmpty());
        }
        for (String role : List.of("AGENTS", "HUMAN_CAPITAL", "VOUCHER_SUPPORT", "MERCHANT_TILL")) {
            mvc.perform(as(role, get(STATEMENT))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get(STATEMENT)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("on the LIVE store the statement is plain Success")
    void live() throws Exception {
        when(service.report(PAYDAY)).thenReturn(report(false, false));

        mvc.perform(as("FINANCE", get(STATEMENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Success"))
                .andExpect(jsonPath("$.data.storeMode").value("LIVE"))
                .andExpect(jsonPath("$.data.simulated").value(false));
    }

    @Test
    @DisplayName("format=csv downloads it, named for the payday; another format and a day not a payday are 400s")
    void formats() throws Exception {
        when(service.csv(PAYDAY)).thenReturn(new StaffLoanCollectionReport.Csv(PAYDAY, true,
                "loanReference,employeeNumber\r\n"));
        mvc.perform(as("FINANCE", get(STATEMENT)).param("format", "CSV"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"staff-loan-collections-2026-11-20-SIMULATED.csv\""))
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(content().string("loanReference,employeeNumber\r\n"));
        mvc.perform(as("FINANCE", get(STATEMENT)).param("format", "xlsx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("format must be json or csv"));

        when(service.report(LocalDate.of(2026, 11, 19))).thenThrow(new ValidationException(
                "2026-11-19 is not a payday: Staff Grocery Loans are collected on day 20 of each month"));
        mvc.perform(as("FINANCE", get("/lending/v1/staff-loan-collections/2026-11-19")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("2026-11-19 is not a payday: Staff Grocery Loans are"
                        + " collected on day 20 of each month"));
        mvc.perform(as("FINANCE", get("/lending/v1/staff-loan-collections/20-11-2026")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("the payday sync: a page at a time, its message says whether there is more; OFF is a 503")
    void paydaySync() throws Exception {
        when(service.syncForPayday(eq(PAYDAY), any(), any())).thenReturn(
                new StaffLoanCollectionReport.Sync(3, 45, 1, 1, 2, 57L));
        for (String role : List.of("FINANCE", "CREDIT_MANAGER", "SUPER_ADMIN")) {
            mvc.perform(as(role, post(STATEMENT + "/getmore-syncs")).param("after", "0").param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Payday 20 Nov 2026: 3 synced, 45 unchanged, 1 blocked,"
                            + " 1 unavailable; 2 cancelled voucher issuances cleaned up; more to sync: repeat with"
                            + " after=57"))
                    .andExpect(jsonPath("$.data.nextAfter").value(57))
                    .andExpect(jsonPath("$.data.cleanedUp").value(2));
        }
        verify(service, org.mockito.Mockito.times(3)).syncForPayday(PAYDAY, 0L, 50);
        mvc.perform(as("HUMAN_CAPITAL", post(STATEMENT + "/getmore-syncs"))).andExpect(status().isForbidden());

        when(service.syncForPayday(eq(PAYDAY), isNull(), isNull())).thenThrow(new GetMoreUnavailableException(null,
                GetMoreUnavailableException.Reason.OFF, "GetMore is OFF on this host (GETMORE_MODE): nothing can be"
                + " read for payday 20 Nov 2026"));
        mvc.perform(as("FINANCE", post(STATEMENT + "/getmore-syncs")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GETMORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("GetMore unavailable (OFF): GetMore is OFF on this host"
                        + " (GETMORE_MODE): nothing can be read for payday 20 Nov 2026"));
        assertThat(StaffLoanCollectionController.syncMessage(PAYDAY, new StaffLoanCollectionReport.Sync(0, 1, 0, 0,
                1, null))).isEqualTo("Payday 20 Nov 2026: 0 synced, 1 unchanged, 0 blocked, 0 unavailable; 1"
                + " cancelled voucher issuance cleaned up; done");
    }

    @Test
    @DisplayName("Finance and SUPER_ADMIN finalise, with or without a body; Credit may not")
    void finalisers() throws Exception {
        when(service.finalise(eq(PAYDAY), any())).thenReturn(report(true, true));
        mvc.perform(as("FINANCE", post(STATEMENT + "/finalisation")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Collections for 20 Nov 2026 finalised: 1 loan, USD 150.00 to"
                        + " collect; USD 90.00 to pay GetMore Groceries (SIMULATED: not for payroll)"))
                .andExpect(jsonPath("$.data.finalised").value(true))
                .andExpect(jsonPath("$.data.finalisedBy").value("finance1"));
        verify(service).finalise(eq(PAYDAY), isNull());

        mvc.perform(as("SUPER_ADMIN", post(STATEMENT + "/finalisation")).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"acceptNonFinal\": true, \"reason\": \"GetMore was down\" }"))
                .andExpect(status().isOk());
        ArgumentCaptor<FinaliseCollectionRequest> request = ArgumentCaptor.forClass(FinaliseCollectionRequest.class);
        verify(service, org.mockito.Mockito.times(2)).finalise(eq(PAYDAY), request.capture());
        assertThat(request.getAllValues().get(1)).isEqualTo(new FinaliseCollectionRequest(true, "GetMore was down"));

        mvc.perform(as("CREDIT_MANAGER", post(STATEMENT + "/finalisation"))).andExpect(status().isForbidden());
        mvc.perform(as("HUMAN_CAPITAL", post(STATEMENT + "/finalisation"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a reason over 255 is a 400 before the service; the service's refusals are 400 and 409")
    void refusals() throws Exception {
        mvc.perform(as("FINANCE", post(STATEMENT + "/finalisation")).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"acceptNonFinal\": true, \"reason\": \"" + "x".repeat(256) + "\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.reason").value("reason must be at most 255 characters"));
        verifyNoInteractions(service);

        when(service.finalise(eq(PAYDAY), any())).thenThrow(new ConflictException("1 line is not final and 1"
                + " voucher is stale: sync first (POST /lending/v1/staff-loan-collections/2026-11-20/getmore-syncs)"
                + " or finalise with acceptNonFinal and a reason"));
        mvc.perform(as("FINANCE", post(STATEMENT + "/finalisation")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("1 line is not final and 1 voucher is stale: sync first (POST"
                        + " /lending/v1/staff-loan-collections/2026-11-20/getmore-syncs) or finalise with"
                        + " acceptNonFinal and a reason"));

        when(service.finalise(eq(LocalDate.of(2026, 12, 20)), any())).thenThrow(new ValidationException(
                "reason is required to finalise with acceptNonFinal"));
        mvc.perform(as("FINANCE", post("/lending/v1/staff-loan-collections/2026-12-20/finalisation"))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"acceptNonFinal\": true }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("reason is required to finalise with acceptNonFinal"));
    }
}
