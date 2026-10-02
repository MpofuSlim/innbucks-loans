package zw.co.innbucks.loans.security;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
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
import zw.co.innbucks.loans.controller.CreditReasonCodeController;
import zw.co.innbucks.loans.controller.VoucherController;
import zw.co.innbucks.loans.controller.VoucherRedemptionController;
import zw.co.innbucks.loans.core.loan.CreditDecisionService;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionService;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementService;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;
import zw.co.innbucks.loans.web.VoucherApiExamples;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The voucher roles are kept to the voucher endpoints by the REAL security chain ({@link ApiSecurityConfig}), even where
 * an endpoint asks for nothing but a valid token: GetMore's till account reaches validation, redemption and its own
 * password change and nothing else, whatever else it holds; VOUCHER_SUPPORT on its own reaches the voucher screens.
 */
@SpringJUnitWebConfig(VoucherRoleFilterTest.Config.class)
class VoucherRoleFilterTest {

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, CreditReasonCodeController.class,
            VoucherController.class, VoucherRedemptionController.class})
    static class Config {
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean CreditDecisionService creditDecisionService;
    @MockitoBean VoucherService voucherService;
    @MockitoBean VoucherSettlementService settlementService;
    @MockitoBean VoucherRedemptionService redemptionService;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        givenToken("till", UserGroup.MERCHANT_TILL);
        givenToken("till-admin", UserGroup.MERCHANT_TILL, UserGroup.SUPER_ADMIN);
        givenToken("support", UserGroup.VOUCHER_SUPPORT);
        givenToken("support-credit", UserGroup.VOUCHER_SUPPORT, UserGroup.CREDIT_MANAGER);
        givenToken("credit", UserGroup.CREDIT_MANAGER);
        when(creditDecisionService.reasonCodes(any())).thenReturn(List.of());
        when(voucherService.vouchers(any(), any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
    }

    private void givenToken(String username, UserGroup... groups) {
        Jwt jwt = Jwt.withTokenValue(username + "-token").header("alg", "HS256")
                .subject(username + "-sub")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", Arrays.stream(groups).map(Enum::name).toList()))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(jwtDecoder.decode(username + "-token")).thenReturn(jwt);
    }

    private static MockHttpServletRequestBuilder as(String username, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + username + "-token");
    }

    @Test
    @DisplayName("MERCHANT_TILL is refused an endpoint that asks only for a valid token, even alongside SUPER_ADMIN")
    void getMoreConfined() throws Exception {
        for (String till : List.of("till", "till-admin")) {
            mvc.perform(as(till, get("/lending/v1/credit-reason-codes")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("Forbidden - insufficient role"));
            mvc.perform(as(till, get("/lending/v1/vouchers"))).andExpect(status().isForbidden());
            mvc.perform(as(till, post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON).content("{}")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("MERCHANT_TILL reaches validation, redemption and its own password change")
    void getMoreReachesItsEndpoints() throws Exception {
        mvc.perform(as("till", post("/lending/v1/voucher-validations").contentType(MediaType.APPLICATION_JSON)
                        .content(VoucherApiExamples.VALIDATION_REQUEST)))
                .andExpect(status().isOk());
        // Past the filter: this context maps no password controller, so a 404 rather than the filter's 403.
        mvc.perform(as("till", put("/lending/v1/me/password").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("VOUCHER_SUPPORT alone reaches the voucher screens only; held with another role, that role's too")
    void voucherSupportConfinedWhenAlone() throws Exception {
        mvc.perform(as("support", get("/lending/v1/vouchers"))).andExpect(status().isOk());
        mvc.perform(as("support", get("/lending/v1/credit-reason-codes"))).andExpect(status().isForbidden());
        mvc.perform(as("support", post("/lending/v1/voucher-validations").contentType(MediaType.APPLICATION_JSON)
                .content(VoucherApiExamples.VALIDATION_REQUEST))).andExpect(status().isForbidden());

        mvc.perform(as("support-credit", get("/lending/v1/credit-reason-codes"))).andExpect(status().isOk());
        mvc.perform(as("support-credit", get("/lending/v1/vouchers"))).andExpect(status().isOk());
        mvc.perform(as("credit", get("/lending/v1/credit-reason-codes"))).andExpect(status().isOk());
    }
}
