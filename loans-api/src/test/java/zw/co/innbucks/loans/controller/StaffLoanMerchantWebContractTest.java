package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.merchant.StaffLoanMerchantResponse;
import zw.co.innbucks.loans.core.merchant.StaffLoanMerchantService;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Staff Grocery Loan's merchant: who may read it, who may change it, and every refusal by its status. */
class StaffLoanMerchantWebContractTest {

    private static final StaffLoanMerchantResponse GETMORE = new StaffLoanMerchantResponse("getmore-groceries",
            "GetMore Groceries", false);

    private StaffLoanMerchantService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffLoanMerchantService.class);
        ProxyFactory secured = new ProxyFactory(new StaffLoanMerchantController(service));
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
                .subject("admin")
                .claim("preferred_username", "admin")
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
    @DisplayName("Credit, Finance, Human Capital and SUPER_ADMIN read it; an agent or a till may not")
    void read() throws Exception {
        when(service.get()).thenReturn(GETMORE);

        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "HUMAN_CAPITAL", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/staff-loan-merchant").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.merchantCode").value("getmore-groceries"))
                    .andExpect(jsonPath("$.data.name").value("GetMore Groceries"))
                    .andExpect(jsonPath("$.data.settlementAccountConfigured").value(false));
        }
        for (String role : List.of("AGENTS", "MERCHANT_TILL", "VOUCHER_SUPPORT")) {
            mvc.perform(get("/lending/v1/staff-loan-merchant").with(as(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("none set is a 404")
    void noneSet() throws Exception {
        when(service.get()).thenThrow(new NotFoundException("No merchant is set for the Staff Grocery Loan"));

        mvc.perform(get("/lending/v1/staff-loan-merchant").with(as("CREDIT_MANAGER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No merchant is set for the Staff Grocery Loan"));
    }

    @Test
    @DisplayName("only SUPER_ADMIN changes it, as themselves; Credit may not")
    void change() throws Exception {
        when(service.change("pick-n-pay", "admin")).thenReturn(new StaffLoanMerchantResponse("pick-n-pay",
                "Pick n Pay", true));

        mvc.perform(put("/lending/v1/staff-loan-merchant").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"merchantCode\":\"pick-n-pay\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/lending/v1/staff-loan-merchant").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"merchantCode\":\"pick-n-pay\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantCode").value("pick-n-pay"))
                .andExpect(jsonPath("$.data.settlementAccountConfigured").value(true));
        verify(service).change("pick-n-pay", "admin");
    }

    @Test
    @DisplayName("no merchantCode is a 400 before the service; a refused or unknown merchant answers by its status")
    void refusals() throws Exception {
        mvc.perform(put("/lending/v1/staff-loan-merchant").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);

        doThrow(new ValidationException("Merchant innbucks is not paid to its own account")).when(service)
                .change(any(), any());
        mvc.perform(put("/lending/v1/staff-loan-merchant").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"merchantCode\":\"innbucks\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Merchant innbucks is not paid to its own account"));

        doThrow(new NotFoundException("Merchant nobody not found")).when(service).change(any(), any());
        mvc.perform(put("/lending/v1/staff-loan-merchant").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"merchantCode\":\"nobody\"}"))
                .andExpect(status().isNotFound());
    }
}
