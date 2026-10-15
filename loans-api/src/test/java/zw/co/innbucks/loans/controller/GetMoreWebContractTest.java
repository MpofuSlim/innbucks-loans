package zw.co.innbucks.loans.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreException;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;
import zw.co.innbucks.loans.core.getmore.GetMoreStatusService;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.getmore.live.LiveAmountModel;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.merchant.VoucherStore;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceRepository;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuanceStatus;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssue;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueKind;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueRepository;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /lending/v1/getmore} and {@code GET /lending/v1/voucher-sync-issues} on the real security chain and the
 * real status service over mocked records: who may read them; the mode and its warnings (OFF, LIVE issuing nothing, a
 * Staff Grocery Loan merchant that is not a GetMore store); the open issuances by state; the sales rule read only when
 * asked, and a rule GetMore cannot give part of the answer, never an error; and the sync issues, open or all.
 */
@SpringJUnitWebConfig(GetMoreWebContractTest.Config.class)
class GetMoreWebContractTest {

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, GetMoreController.class,
            GetMoreStatusService.class})
    static class Config {

        @Bean
        GetMoreProperties getMoreProperties() {
            return new GetMoreProperties();
        }
    }

    private static final GetMoreSalesRule RULE_43 = new GetMoreSalesRule(43, "InnBucks Staff Grocery (SIMULATED)",
            true, null, null, "cart_fixed", new BigDecimal("0.0000"), "AUTO", true, 0, 0, true, false);

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean GetMoreAmountBinding binding;
    @MockitoBean GetMoreCoupons coupons;
    @MockitoBean MerchantRepository merchantRepository;
    @MockitoBean StaffLoanVoucherIssuanceRepository issuanceRepository;
    @MockitoBean VoucherSyncIssueRepository issueRepository;
    @MockitoBean VoucherRepository voucherRepository;
    @MockitoBean PlatformTransactionManager transactionManager;

    @Autowired WebApplicationContext context;
    @Autowired GetMoreProperties properties;

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
        properties.setMode(GetMoreMode.SIMULATED);
        properties.setRuleId(43);
        properties.getLive().setBaseUrl("https://shop.getmore.co.zw/rest/");
        properties.getLive().setAmountModel(LiveAmountModel.NONE);
        when(binding.name()).thenReturn("SIMULATED_PER_COUPON");
        when(binding.partialSpending()).thenReturn(true);
        when(merchantRepository.findByStaffLoanMerchantTrue()).thenReturn(Optional.of(merchant(VoucherStore.GETMORE)));
        when(issuanceRepository.countByStatusAndStoreMode(any(), any())).thenReturn(0L);
        when(issuanceRepository.countByStatusAndStoreMode(StaffLoanVoucherIssuanceStatus.PARKED, GetMoreMode.SIMULATED))
                .thenReturn(1L);
        when(issueRepository.countOpenForStoreMode(GetMoreMode.SIMULATED)).thenReturn(1L);
        when(voucherRepository.countByStoreModeAndSyncBlockedByIsNotNull(GetMoreMode.SIMULATED)).thenReturn(1L);
        when(voucherRepository.countByStoreModeAndHeldAmountGreaterThan(GetMoreMode.SIMULATED, BigDecimal.ZERO))
                .thenReturn(2L);
        when(coupons.rule(43)).thenReturn(RULE_43);
    }

    private static Merchant merchant(VoucherStore store) {
        return Merchant.builder().merchantCode("getmore-groceries").companyName("GetMore Groceries")
                .voucherStore(store).build();
    }

    private static MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + role + "-token");
    }

    @Test
    @DisplayName("SUPER_ADMIN, CREDIT_MANAGER and FINANCE read both; everyone else is refused, anonymous with a 401")
    void readers() throws Exception {
        when(issueRepository.findOpenForStoreMode(any(), any())).thenReturn(Page.empty());
        for (String role : List.of("SUPER_ADMIN", "CREDIT_MANAGER", "FINANCE")) {
            mvc.perform(as(role, get("/lending/v1/getmore"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"));
            mvc.perform(as(role, get("/lending/v1/voucher-sync-issues"))).andExpect(status().isOk());
        }
        for (String role : List.of("AGENTS", "HUMAN_CAPITAL", "VOUCHER_SUPPORT", "MERCHANT_TILL")) {
            mvc.perform(as(role, get("/lending/v1/getmore"))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(as(role, get("/lending/v1/voucher-sync-issues"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/lending/v1/getmore")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/lending/v1/voucher-sync-issues")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SIMULATED: the mode, prefix, model, merchant, counts and no warning; the rule only when asked")
    void simulated() throws Exception {
        mvc.perform(as("CREDIT_MANAGER", get("/lending/v1/getmore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("SIMULATED"))
                .andExpect(jsonPath("$.data.ruleId").value(43))
                .andExpect(jsonPath("$.data.couponPrefix").value("SIM-"))
                .andExpect(jsonPath("$.data.liveHost").isEmpty())
                .andExpect(jsonPath("$.data.amountModel").value("SIMULATED_PER_COUPON"))
                .andExpect(jsonPath("$.data.partialSpending").value(true))
                .andExpect(jsonPath("$.data.staffLoanMerchant.merchantCode").value("getmore-groceries"))
                .andExpect(jsonPath("$.data.staffLoanMerchant.voucherStore").value("GETMORE"))
                .andExpect(jsonPath("$.data.warnings").isEmpty())
                .andExpect(jsonPath("$.data.issuances.PENDING").value(0))
                .andExpect(jsonPath("$.data.issuances.PARKED").value(1))
                .andExpect(jsonPath("$.data.issuances.ISSUED").doesNotExist())
                .andExpect(jsonPath("$.data.openSyncIssues").value(1))
                .andExpect(jsonPath("$.data.blockedVouchers").value(1))
                .andExpect(jsonPath("$.data.heldVouchers").value(2))
                .andExpect(jsonPath("$.data.rule").doesNotExist())
                .andExpect(jsonPath("$.data.ruleError").doesNotExist());
        verify(coupons, never()).rule(anyLong());

        mvc.perform(as("FINANCE", get("/lending/v1/getmore").param("checkRule", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rule.ruleId").value(43))
                .andExpect(jsonPath("$.data.rule.simpleAction").value("cart_fixed"))
                .andExpect(jsonPath("$.data.ruleError").doesNotExist());
    }

    @Test
    @DisplayName("LIVE: GetMore's host, a warning while no amount model is set, and an unreadable rule as ruleError")
    void liveRuleUnreadable() throws Exception {
        properties.setMode(GetMoreMode.LIVE);
        when(binding.name()).thenReturn("NONE");
        when(binding.partialSpending()).thenReturn(false);
        when(coupons.rule(43)).thenThrow(new GetMoreUnavailableException(GetMoreException.Operation.SALES_RULE,
                GetMoreUnavailableException.Reason.AUTH, 401, "the integration token was refused"));

        mvc.perform(as("SUPER_ADMIN", get("/lending/v1/getmore").param("checkRule", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("LIVE"))
                .andExpect(jsonPath("$.data.liveHost").value("shop.getmore.co.zw"))
                .andExpect(jsonPath("$.data.amountModel").value("NONE"))
                .andExpect(jsonPath("$.data.warnings[0]").value("LIVE reads GetMore but issues no voucher until"
                        + " GETMORE_LIVE_AMOUNT_MODEL is set"))
                .andExpect(jsonPath("$.data.rule").doesNotExist())
                .andExpect(jsonPath("$.data.ruleError").value("GETMORE_UNAVAILABLE: AUTH"));

        doThrow(new GetMoreRejectedException(GetMoreException.Operation.SALES_RULE, 404,
                GetMoreRejectedException.Reason.NOT_FOUND, "No such entity with ruleId = 43")).when(coupons).rule(43);
        mvc.perform(as("SUPER_ADMIN", get("/lending/v1/getmore").param("checkRule", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ruleError").value("GETMORE_REJECTED: NOT_FOUND"));
    }

    @Test
    @DisplayName("counts are of this host's store only: a host moved to LIVE never reports its simulated rows")
    void countsOfThisHostsStoreOnly() throws Exception {
        properties.setMode(GetMoreMode.LIVE);
        when(issuanceRepository.countByStatusAndStoreMode(StaffLoanVoucherIssuanceStatus.PENDING, GetMoreMode.LIVE))
                .thenReturn(3L);

        mvc.perform(as("CREDIT_MANAGER", get("/lending/v1/getmore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("LIVE"))
                .andExpect(jsonPath("$.data.issuances.PENDING").value(3))
                .andExpect(jsonPath("$.data.issuances.PARKED").value(0))
                .andExpect(jsonPath("$.data.openSyncIssues").value(0))
                .andExpect(jsonPath("$.data.blockedVouchers").value(0))
                .andExpect(jsonPath("$.data.heldVouchers").value(0));

        properties.setMode(GetMoreMode.OFF);
        mvc.perform(as("CREDIT_MANAGER", get("/lending/v1/getmore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issuances.PENDING").value(3))
                .andExpect(jsonPath("$.data.openSyncIssues").value(0));
        verify(issueRepository, times(2)).countOpenForStoreMode(GetMoreMode.LIVE);
        verify(issueRepository, never()).countOpenForStoreMode(GetMoreMode.SIMULATED);

        // The list Credit opens from that count is of the same store: a simulated voucher's issue, which can never
        // clear on this host, is not listed beside the live ones.
        when(issueRepository.findOpenForStoreMode(any(), any())).thenReturn(Page.empty());
        when(issueRepository.findAllForStoreMode(any(), any())).thenReturn(Page.empty());
        for (GetMoreMode mode : List.of(GetMoreMode.LIVE, GetMoreMode.OFF)) {
            properties.setMode(mode);
            mvc.perform(as("CREDIT_MANAGER", get("/lending/v1/voucher-sync-issues")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
            mvc.perform(as("CREDIT_MANAGER", get("/lending/v1/voucher-sync-issues").param("open", "false")))
                    .andExpect(status().isOk());
        }
        verify(issueRepository, times(2)).findOpenForStoreMode(eq(GetMoreMode.LIVE), any());
        verify(issueRepository, times(2)).findAllForStoreMode(eq(GetMoreMode.LIVE), any());
        verify(issueRepository, never()).findOpenForStoreMode(eq(GetMoreMode.SIMULATED), any());
        verify(issueRepository, never()).findAllForStoreMode(eq(GetMoreMode.SIMULATED), any());
    }

    @Test
    @DisplayName("OFF: one warning that accepted loans wait with no voucher, and no merchant warning on top")
    void off() throws Exception {
        properties.setMode(GetMoreMode.OFF);
        when(merchantRepository.findByStaffLoanMerchantTrue()).thenReturn(Optional.of(merchant(VoucherStore.LOANS)));

        mvc.perform(as("CREDIT_MANAGER", get("/lending/v1/getmore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("OFF"))
                .andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings[0]").value("GetMore is OFF: an accepted Staff Grocery Loan"
                        + " waits AWAITING_DISBURSEMENT with no voucher. Set GETMORE_MODE."));
    }

    @Test
    @DisplayName("GetMore on with a Staff Grocery Loan merchant that is not a GetMore store: the merchant warning")
    void merchantWarning() throws Exception {
        when(merchantRepository.findByStaffLoanMerchantTrue()).thenReturn(Optional.of(merchant(VoucherStore.LOANS)));

        mvc.perform(as("CREDIT_MANAGER", get("/lending/v1/getmore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staffLoanMerchant.voucherStore").value("LOANS"))
                .andExpect(jsonPath("$.data.warnings[0]").value("Accepted loans will wait AWAITING_DISBURSEMENT with"
                        + " no voucher: the Staff Grocery Loan merchant getmore-groceries is not a GetMore store"));

        when(merchantRepository.findByStaffLoanMerchantTrue()).thenReturn(Optional.empty());
        mvc.perform(as("CREDIT_MANAGER", get("/lending/v1/getmore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.staffLoanMerchant").isEmpty())
                .andExpect(jsonPath("$.data.warnings").isEmpty());
    }

    @Test
    @DisplayName("sync issues: the open ones by default, every one with open=false, each naming its loan, paged")
    void syncIssues() throws Exception {
        VoucherSyncIssue overspent = VoucherSyncIssue.builder().id(4L).voucherId(12L)
                .kind(VoucherSyncIssueKind.OVERSPENT).blocking(true).externalRef("order:21")
                .detail("order SIM000000021 spent 80.00, which would take the voucher to 230.00 spent of 150.00")
                .firstSeenAt(LocalDateTime.of(2026, 10, 14, 9, 2, 44))
                .lastSeenAt(LocalDateTime.of(2026, 10, 14, 9, 15, 9)).build();
        when(issueRepository.findOpenForStoreMode(eq(GetMoreMode.SIMULATED), any()))
                .thenAnswer(call -> new PageImpl<>(List.of(overspent), call.<Pageable>getArgument(1), 1));
        when(issueRepository.findAllForStoreMode(eq(GetMoreMode.SIMULATED), any()))
                .thenAnswer(call -> new PageImpl<>(List.of(overspent), call.<Pageable>getArgument(1), 1));
        when(voucherRepository.findAllById(List.of(12L)))
                .thenReturn(List.of(Voucher.builder().id(12L).loanAccount("SGL-2026-000171").build()));

        mvc.perform(as("FINANCE", get("/lending/v1/voucher-sync-issues")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(4))
                .andExpect(jsonPath("$.data.items[0].voucherId").value(12))
                .andExpect(jsonPath("$.data.items[0].loanAccount").value("SGL-2026-000171"))
                .andExpect(jsonPath("$.data.items[0].kind").value("OVERSPENT"))
                .andExpect(jsonPath("$.data.items[0].blocking").value(true))
                .andExpect(jsonPath("$.data.items[0].clearedAt").isEmpty())
                .andExpect(jsonPath("$.data.size").value(20));
        verify(issueRepository).findOpenForStoreMode(GetMoreMode.SIMULATED, PageRequest.of(0, 20));
        verify(issueRepository, never()).findAllForStoreMode(any(), any());

        mvc.perform(as("FINANCE", get("/lending/v1/voucher-sync-issues").param("open", "false")
                        .param("page", "1").param("size", "500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(100));
        verify(issueRepository).findAllForStoreMode(eq(GetMoreMode.SIMULATED), argThat(page ->
                page.getPageNumber() == 1 && page.getPageSize() == 100));
    }
}
