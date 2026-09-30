package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.authority.CreateCreditAuthorityLevelRequest;
import zw.co.innbucks.loans.core.authority.CreditAuthorityLevelResponse;
import zw.co.innbucks.loans.core.authority.CreditAuthorityService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.user.AdminPasswordResetService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor.preAuthorize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The credit approval limit endpoints (FR-PBL-028): staff read the levels, only SUPER_ADMIN changes them or gives a user
 * one, and the requests are validated before the service is called. Behind production's {@code @PreAuthorize}
 * interceptor; no database, no Spring context.
 */
class CreditAuthorityWebContractTest {

    private static final CreditAuthorityLevelResponse SENIOR = new CreditAuthorityLevelResponse("SENIOR_CREDIT_OFFICER",
            "Senior credit officer", new BigDecimal("2500.00"), List.of(), "admin", null);

    private CreditAuthorityService creditAuthorityService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        creditAuthorityService = mock(CreditAuthorityService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        secured(new CreditAuthorityController(creditAuthorityService)),
                        secured(new UserController(mock(AdminPasswordResetService.class), creditAuthorityService)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Object secured(Object controller) {
        ProxyFactory secured = new ProxyFactory(controller);
        secured.setProxyTargetClass(true);
        secured.addAdvisor(preAuthorize());
        return secured.getProxy();
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
    @DisplayName("staff read the levels; agents are refused")
    void staffReadTheLevels() throws Exception {
        when(creditAuthorityService.levels()).thenReturn(List.of(SENIOR));

        mvc.perform(get("/lending/v1/credit-authority-levels").with(as("AGENTS"))).andExpect(status().isForbidden());
        for (String role : List.of("CREDIT_MANAGER", "FINANCE", "SUPER_ADMIN")) {
            mvc.perform(get("/lending/v1/credit-authority-levels").with(as(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].code").value("SENIOR_CREDIT_OFFICER"));
        }
    }

    @Test
    @DisplayName("only SUPER_ADMIN adds, changes or removes a level; adding answers 201, a bad code 400, a clash 409")
    void onlySuperAdminChangesLevels() throws Exception {
        String request = ApiExamples.CREDIT_AUTHORITY_LEVEL_REQUEST;
        for (String role : List.of("CREDIT_MANAGER", "FINANCE")) {
            mvc.perform(post("/lending/v1/credit-authority-levels").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/lending/v1/credit-authority-levels/SENIOR_CREDIT_OFFICER").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ApiExamples.CREDIT_AUTHORITY_LEVEL_UPDATE_REQUEST))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/lending/v1/credit-authority-levels/SENIOR_CREDIT_OFFICER").with(as(role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(creditAuthorityService);

        when(creditAuthorityService.create(any())).thenReturn(SENIOR);
        mvc.perform(post("/lending/v1/credit-authority-levels").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.message")
                        .value("Credit authority level created; it limits approvals by whoever is given it"))
                .andExpect(jsonPath("$.data.maximumPrincipal").value(2500.00));
        ArgumentCaptor<CreateCreditAuthorityLevelRequest> created =
                ArgumentCaptor.forClass(CreateCreditAuthorityLevelRequest.class);
        verify(creditAuthorityService).create(created.capture());
        assertThat(created.getValue().getMaximumPrincipal()).isEqualByComparingTo("2500");

        mvc.perform(post("/lending/v1/credit-authority-levels").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"senior\",\"name\":\"Senior\",\"maximumPrincipal\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value(
                        "Code must be 3 to 40 capital letters, digits or underscores, starting with a letter"))
                .andExpect(jsonPath("$.data.maximumPrincipal").value("Maximum principal must be more than zero"));

        doThrow(new ConflictException("Senior credit officer is held by rnyathi; give them another level first"))
                .when(creditAuthorityService).delete("SENIOR_CREDIT_OFFICER");
        mvc.perform(delete("/lending/v1/credit-authority-levels/SENIOR_CREDIT_OFFICER").with(as("SUPER_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Senior credit officer is held by rnyathi; give them another level first"));
        mvc.perform(delete("/lending/v1/credit-authority-levels/HEAD_OF_CREDIT").with(as("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Credit authority level removed"));
    }

    @Test
    @DisplayName("only SUPER_ADMIN gives a user a level, and the answer says what it is now")
    void onlySuperAdminGivesALevel() throws Exception {
        mvc.perform(put("/lending/v1/users/3/credit-authority-level").with(as("CREDIT_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.USER_CREDIT_AUTHORITY_REQUEST))
                .andExpect(status().isForbidden());
        verifyNoInteractions(creditAuthorityService);

        when(creditAuthorityService.assign(3L, "SENIOR_CREDIT_OFFICER")).thenReturn(UserResponse.builder().id(3L)
                .username("rnyathi").creditAuthorityLevel("SENIOR_CREDIT_OFFICER").build());
        mvc.perform(put("/lending/v1/users/3/credit-authority-level").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(ApiExamples.USER_CREDIT_AUTHORITY_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Credit authority level set to SENIOR_CREDIT_OFFICER"))
                .andExpect(jsonPath("$.data.creditAuthorityLevel").value("SENIOR_CREDIT_OFFICER"));

        when(creditAuthorityService.assign(eq(3L), eq(null))).thenReturn(UserResponse.builder().id(3L)
                .username("rnyathi").build());
        mvc.perform(put("/lending/v1/users/3/credit-authority-level").with(as("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"level\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Credit authority level removed"));
    }
}
