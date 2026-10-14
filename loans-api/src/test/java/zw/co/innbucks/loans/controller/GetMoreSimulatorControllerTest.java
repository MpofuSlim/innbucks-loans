package zw.co.innbucks.loans.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.simulator.GetMoreSimulatorActions;
import zw.co.innbucks.loans.core.getmore.simulator.SimLookup;
import zw.co.innbucks.loans.core.getmore.simulator.SimSale;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedTill;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorApiKey;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorFaults;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorRefusal;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorRefusedException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.staff.loan.StaffLoan;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanRepository;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncTrigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherSync;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherCodeSource;
import zw.co.innbucks.loans.core.voucher.VoucherCodeVault;
import zw.co.innbucks.loans.core.voucher.VoucherDeliveryStatus;
import zw.co.innbucks.loans.core.voucher.VoucherProduct;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GetMoreApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staging's GetMore test actions, switched on (SIMULATED), behind the REAL security chain and the real actions service
 * over a mocked store: no loans token, but the api key, checked before anything reads the body; each body validated
 * field by field; each till refusal its own code and status; a sale created (201) and a replay answered as before
 * (200), synced into loans; and every use audited with who called and never the key or a code.
 */
@SpringJUnitWebConfig(GetMoreSimulatorControllerTest.Config.class)
@TestPropertySource(properties = "loans.getmore.mode=SIMULATED")
class GetMoreSimulatorControllerTest {

    static final String API_KEY = "staging-getmore-simulator-key-0123456789abcdef";
    private static final String BASE = "/lending/v1/getmore-simulator";

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, GetMoreSimulatorController.class,
            GetMoreSimulatorActions.class, SimulatorApiKey.class, SimulatorFaults.class})
    static class Config {

        @Bean
        GetMoreProperties getMoreProperties() {
            GetMoreProperties properties = new GetMoreProperties();
            properties.setMode(GetMoreMode.SIMULATED);
            properties.getSimulator().setApiKey(API_KEY);
            return properties;
        }

        @Bean
        MarketTimeZone marketTimeZone() {
            return new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-14T08:31:00Z"), ZoneOffset.UTC));
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean SimulatedTill till;
    @MockitoBean GetMoreVoucherSync sync;
    @MockitoBean VoucherService voucherService;
    @MockitoBean VoucherRepository voucherRepository;
    @MockitoBean StaffLoanRepository loanRepository;
    @MockitoBean VoucherCodeVault vault;
    @MockitoBean AuditService auditService;
    @MockitoBean PlatformTransactionManager transactionManager;

    @Autowired WebApplicationContext context;
    @Autowired MeterRegistry meterRegistry;
    @Autowired SimulatorFaults faults;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        faults.clear();
        StaffLoan loan = mock(StaffLoan.class);
        when(loan.getId()).thenReturn(164L);
        when(loanRepository.findByReference("SGL-2026-000164")).thenReturn(Optional.of(loan));
        when(voucherRepository.findByStaffLoanId(164L)).thenReturn(Optional.of(voucher9()));
        when(voucherRepository.findByStoreModeAndExternalCouponId(GetMoreMode.SIMULATED, 1207L))
                .thenReturn(Optional.of(voucher9()));
        when(vault.decrypt("sealed")).thenReturn(GetMoreApiExamples.CODE_9);
        when(sync.sync(9L, GetMoreSyncTrigger.SIMULATOR)).thenReturn(new GetMoreSyncResult(
                GetMoreSyncResult.Outcome.SYNCED, 1, 0, new BigDecimal("0.00"), 1, List.of(), false, voucher9View()));
    }

    private static Voucher voucher9() {
        return voucher9(GetMoreMode.SIMULATED);
    }

    private static Voucher voucher9(GetMoreMode storeMode) {
        return Voucher.builder().id(9L).product(VoucherProduct.STAFF_GROCERY_LOAN)
                .merchant(Merchant.builder().merchantCode("getmore-groceries").companyName("GetMore Groceries")
                        .build())
                .loanAccount("SGL-2026-000164").codeCiphertext("sealed").faceValue(new BigDecimal("150.00"))
                .redeemedAmount(new BigDecimal("0.00")).currency("USD")
                .issuedAt(LocalDateTime.of(2026, 10, 14, 8, 20, 7))
                .expiresAt(LocalDateTime.of(2026, 11, 13, 21, 59, 59))
                .status(VoucherStatus.ISSUED).codeSource(VoucherCodeSource.GETMORE).storeMode(storeMode)
                .staffLoanId(164L).externalCouponId(1207L).build();
    }

    private static VoucherResponse voucher9View() {
        return new VoucherResponse(9L, VoucherProduct.STAFF_GROCERY_LOAN, "getmore-groceries", "GetMore Groceries",
                "SGL-2026-000164", "GETMORE-SIM-1207", "E1064", "Rutendo Chari", "****0064", "****8B1C",
                new BigDecimal("150.00"), new BigDecimal("45.50"), new BigDecimal("104.50"), "USD",
                LocalDateTime.of(2026, 10, 14, 8, 20, 7), LocalDateTime.of(2026, 11, 13, 21, 59, 59),
                VoucherStatus.PARTIALLY_REDEEMED, LocalDateTime.of(2026, 10, 14, 8, 31, 2), "GetMore Avondale", null,
                null, null, VoucherDeliveryStatus.SENT, null, LocalDateTime.of(2026, 10, 14, 8, 20, 8),
                VoucherCodeSource.GETMORE, GetMoreMode.SIMULATED, 1207L, new BigDecimal("0.00"),
                new BigDecimal("0.00"), null, LocalDateTime.of(2026, 10, 14, 8, 31, 2), null, null);
    }

    private static SimSale sale(boolean replayed) {
        return new SimSale(12L, "SIM000000012", 1207L, Map.of("increment_id", "SIM000000012", "coupon_code",
                "****8B1C"), new BigDecimal("45.5000"), "USD", new BigDecimal("104.5000"), replayed);
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request) {
        return request.header("X-Api-Key", API_KEY).header("X-Forwarded-For", "196.27.112.45, 10.0.4.1");
    }

    private static MockHttpServletRequestBuilder sell(String body) {
        return withKey(post(BASE + "/sales")).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @DisplayName("a sale: 201 CREATED with the action's own message, the store's answer, the voucher and the sync")
    void sale() throws Exception {
        double salesBefore = sales();
        when(till.sell(eq(GetMoreApiExamples.CODE_9), any(), eq("USD"), any(), eq("GM-AVD"), eq("GetMore Avondale"),
                eq("AVD-T3-000981"), eq(true))).thenReturn(sale(false));

        mvc.perform(sell(GetMoreApiExamples.SALE_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("Sale SIM000000012: USD 45.50 off the voucher; USD 104.50"
                        + " left"))
                .andExpect(jsonPath("$.data.store.order.coupon_code").value("****8B1C"))
                .andExpect(jsonPath("$.data.store.replayed").value(false))
                .andExpect(jsonPath("$.data.voucher.id").value(9))
                .andExpect(jsonPath("$.data.sync.outcome").value("SYNCED"))
                .andExpect(jsonPath("$.data.sync.spendsRecorded").value(1))
                .andExpect(jsonPath("$.data.couponWithdrawn").doesNotExist())
                .andExpect(jsonPath("$.data.assumptions[1]").value("A2 spending in parts"));
        verify(sync).sync(9L, GetMoreSyncTrigger.SIMULATOR);
        assertThat(sales()).isEqualTo(salesBefore + 1);
    }

    private double sales() {
        var counter = meterRegistry.find("loans.getmore.simulator.actions").tag("action", "SALE").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("the same reference again: 200 OK, the first sale, nothing more spent")
    void replay() throws Exception {
        when(till.sell(any(), any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(sale(true));

        mvc.perform(sell(GetMoreApiExamples.SALE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("Sale SIM000000012 was already made under reference"
                        + " AVD-T3-000981; nothing more was spent"))
                .andExpect(jsonPath("$.data.store.replayed").value(true));
    }

    @Test
    @DisplayName("every use is audited as the simulator, with who called and the key's fingerprint, never the key or"
            + " the code")
    void audited() throws Exception {
        when(till.sell(any(), any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(sale(false));

        mvc.perform(sell(GetMoreApiExamples.SALE_REQUEST)).andExpect(status().isCreated());

        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog row = audit.getValue().build();
        assertThat(row.getEventType()).isEqualTo("GETMORE_SIMULATOR_SALE");
        assertThat(row.getActorId()).isEqualTo("getmore-simulator");
        assertThat(row.getChannelUsed()).isEqualTo("getmore-simulator");
        assertThat(row.getEntityType()).isEqualTo("VOUCHER");
        assertThat(row.getEntityId()).isEqualTo("9");
        assertThat(row.getDetail()).startsWith("ip:127.0.0.1;xff:196.27.112.45, 10.0.4.1;key:"
                + SimulatorApiKey.fingerprint(API_KEY)).contains(";code:****8B1C", ";order:SIM000000012",
                ";outcome:OK").doesNotContain(API_KEY).doesNotContain(GetMoreApiExamples.CODE_9);

        clearInvocations(auditService);
        when(till.sell(any(), any(), any(), any(), any(), any(), any(), anyBoolean())).thenThrow(
                new SimulatorRefusedException(SimulatorRefusal.SIM_INSUFFICIENT_BALANCE, "The amount is more than the"
                        + " USD 50.00 left on this coupon"));
        mvc.perform(sell(GetMoreApiExamples.SALE_REQUEST)).andExpect(status().isConflict());
        verify(auditService).record(audit.capture());
        assertThat(audit.getValue().build().getDetail()).endsWith(";outcome:SIM_INSUFFICIENT_BALANCE");
    }

    @Test
    @DisplayName("each till refusal is its own code and status in the envelope")
    void refusals() throws Exception {
        for (SimulatorRefusal refusal : SimulatorRefusal.values()) {
            doThrow(new SimulatorRefusedException(refusal)).when(till)
                    .sell(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
            mvc.perform(sell(GetMoreApiExamples.SALE_REQUEST))
                    .andExpect(status().is(refusal.httpStatus()))
                    .andExpect(jsonPath("$.code").value(refusal.name()))
                    .andExpect(jsonPath("$.message").value(refusal.message()));
        }
    }

    @Test
    @DisplayName("a loan with no GetMore voucher is a 404, before the till is asked")
    void noVoucher() throws Exception {
        mvc.perform(sell(GetMoreApiExamples.SALE_REQUEST.replace("SGL-2026-000164", "SGL-2026-000999")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No GetMore voucher for loan SGL-2026-000999"));
        verifyNoInteractions(till);
    }

    @Test
    @DisplayName("a body is validated field by field: one of loanReference and code, cents, an outlet")
    void validation() throws Exception {
        mvc.perform(sell("""
                        { "loanReference": "SGL-2026-000164", "code": "SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C",
                          "amount": 45.505, "basketTotal": 10.00, "outletId": "GM AVD", "currency": "usd" }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.loanReferenceOrCode").value("give exactly one of loanReference and code"))
                .andExpect(jsonPath("$.data.amount").value("amount must have at most 2 decimal places and 8 digits"
                        + " before them"))
                .andExpect(jsonPath("$.data.basketTotalAtLeastAmount").value("basketTotal must be at least amount"))
                .andExpect(jsonPath("$.data.outletId").exists())
                .andExpect(jsonPath("$.data.currency").exists());
        mvc.perform(sell("{ \"amount\": 1.00, \"outletId\": \"GM-AVD\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.loanReferenceOrCode").exists());
        mvc.perform(withKey(post(BASE + "/faults")).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"operation\": \"GENERATE\", \"kind\": \"LOSE_RESPONSE\", \"times\": 11 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.times").value("times must be 1 to 10"));
        verifyNoInteractions(till);
    }

    @Test
    @DisplayName("without the api key: 401, even with a loans token, and the body is never parsed")
    void apiKeyFirst() throws Exception {
        when(jwtDecoder.decode("credit-token")).thenReturn(Jwt.withTokenValue("credit-token").header("alg", "HS256")
                .subject("credit").claim("preferred_username", "credit1")
                .claim("realm_access", Map.of("roles", List.of("SUPER_ADMIN")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());

        for (MockHttpServletRequestBuilder request : List.of(
                post(BASE + "/sales").contentType(MediaType.APPLICATION_JSON).content("{ not json"),
                post(BASE + "/sales").header("X-Api-Key", API_KEY + "x").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                post(BASE + "/sales").header("Authorization", "Bearer credit-token")
                        .contentType(MediaType.APPLICATION_JSON).content(GetMoreApiExamples.SALE_REQUEST),
                delete(BASE + "/faults"))) {
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Invalid or missing api key"));
        }
        verifyNoInteractions(till, auditService);
    }

    @Test
    @DisplayName("a body declared over 8 KiB: 413, unread")
    void tooLarge() throws Exception {
        mvc.perform(sell("{\"outletName\": \"" + "x".repeat(9000) + "\"}"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        verifyNoInteractions(till);
    }

    @Test
    @DisplayName("a body with no declared length is cut off past 8 KiB: 413 through the real chain, the till never asked")
    void tooLargeUndeclared() throws Exception {
        String body = GetMoreApiExamples.SALE_REQUEST.replace("GetMore Avondale", "x".repeat(9000));
        mvc.perform(servletContext -> {
                    MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "POST",
                            BASE + "/sales") {
                        @Override
                        public int getContentLength() {
                            return -1;
                        }

                        @Override
                        public long getContentLengthLong() {
                            return -1;
                        }
                    };
                    request.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    request.setContent(body.getBytes(StandardCharsets.UTF_8));
                    request.addHeader("X-Api-Key", API_KEY);
                    return request;
                })
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        verifyNoInteractions(till);
    }

    @Test
    @DisplayName("faults: queued (201) and cleared (200), with what is pending")
    void faults() throws Exception {
        mvc.perform(withKey(post(BASE + "/faults")).contentType(MediaType.APPLICATION_JSON)
                        .content(GetMoreApiExamples.FAULT_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message").value("The next 1 GENERATE call(s) will LOSE_RESPONSE"))
                .andExpect(jsonPath("$.data.pending.GENERATE[0]").value("LOSE_RESPONSE"));
        assertThat(faults.pending()).containsOnlyKeys(Operation.GENERATE);

        mvc.perform(withKey(delete(BASE + "/faults")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Faults cleared"))
                .andExpect(jsonPath("$.data.pending").isEmpty());
        assertThat(faults.pending()).isEmpty();
        verify(auditService, atLeastOnce()).record(any());
    }

    @Test
    @DisplayName("the store's view and a refusal to expire a voucher that is not simulated")
    void lookupAndExpiry() throws Exception {
        when(voucherRepository.findByStaffLoanId(164L)).thenReturn(Optional.of(voucher9(GetMoreMode.LIVE)));
        when(till.lookup("SIM-NOPE0000-0000000000000000"))
                .thenThrow(new SimulatorRefusedException(SimulatorRefusal.SIM_COUPON_NOT_FOUND));

        mvc.perform(withKey(post(BASE + "/coupon-expiries")).contentType(MediaType.APPLICATION_JSON)
                        .content(GetMoreApiExamples.EXPIRY_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_A_SIMULATED_VOUCHER"));
        verify(till, never()).expire(anyString(), any());
        verify(voucherService, never()).expireNowForSimulation(any());

        mvc.perform(withKey(post(BASE + "/coupon-lookups")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"SIM-NOPE0000-0000000000000000\"}"))
                .andExpect(status().isNotFound());
        verify(till).lookup("SIM-NOPE0000-0000000000000000");
    }

    @Test
    @DisplayName("what the store no longer has is absent, not null: a withdrawn coupon, and a coupon that is no voucher's")
    void absentNotNull() throws Exception {
        when(till.lookup(GetMoreApiExamples.CODE_9)).thenReturn(new SimLookup(null,
                Map.of("rule_id", 43), Map.of("items", List.of(), "total_count", 0),
                Map.of("items", List.of(), "total_count", 0), new BigDecimal("150.00"), "USD",
                new BigDecimal("60.00"), true, List.of("A1 per-coupon allowance")));
        mvc.perform(withKey(post(BASE + "/coupon-lookups")).contentType(MediaType.APPLICATION_JSON)
                        .content(GetMoreApiExamples.LOOKUP_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawn").value(true))
                .andExpect(jsonPath("$.data.store.coupon").doesNotExist())
                .andExpect(jsonPath("$.data.store.rule.rule_id").value(43));

        when(voucherRepository.findByStoreModeAndExternalCouponId(GetMoreMode.SIMULATED, 1207L))
                .thenReturn(Optional.empty());
        when(till.sell(any(), any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(sale(false));
        mvc.perform(sell(GetMoreApiExamples.SALE_REQUEST.replace("\"loanReference\": \"SGL-2026-000164\"",
                        "\"code\": \"" + GetMoreApiExamples.CODE_9 + "\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.store").exists())
                .andExpect(jsonPath("$.data.voucher").doesNotExist())
                .andExpect(jsonPath("$.data.sync").doesNotExist());
    }

    @Test
    @DisplayName("an invoice and a refund each sync the coupon's voucher; a refund's replay is a 200")
    void invoiceAndRefund() throws Exception {
        when(till.invoice("SIM000000013")).thenReturn(new SimSale(13L, "SIM000000013", 1207L, Map.of(),
                new BigDecimal("54.5000"), "USD", new BigDecimal("50.0000"), false));
        mvc.perform(withKey(post(BASE + "/sales/SIM000000013/invoices")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sale SIM000000013 invoiced: USD 54.50 off the voucher"));

        when(till.refund(eq("SIM000000012"), any(), eq("AVD-RF-0012"))).thenReturn(
                new zw.co.innbucks.loans.core.getmore.simulator.SimRefund(3L, "SIMCM00000003", 12L, "SIM000000012",
                        1207L, Map.of(), new BigDecimal("10.00"), "USD", new BigDecimal("60.0000"), true));
        mvc.perform(withKey(post(BASE + "/refunds")).contentType(MediaType.APPLICATION_JSON)
                        .content(GetMoreApiExamples.REFUND_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Credit memo SIMCM00000003 was already made under reference"
                        + " AVD-RF-0012; nothing more was refunded"));
        verify(till).refund(eq("SIM000000012"), eq(new BigDecimal("10.00")), eq("AVD-RF-0012"));
        verify(sync, org.mockito.Mockito.times(2)).sync(9L, GetMoreSyncTrigger.SIMULATOR);
        verify(till, never()).sell(any(), any(), any(), any(), any(), any(), isNull(), anyBoolean());
    }
}
