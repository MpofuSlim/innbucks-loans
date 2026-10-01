package zw.co.innbucks.loans.security;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import zw.co.innbucks.loans.controller.BorrowerAuthController;
import zw.co.innbucks.loans.controller.BorrowerController;
import zw.co.innbucks.loans.controller.CreditReasonCodeController;
import zw.co.innbucks.loans.controller.TestAssertionController;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.borrower.AssertionRejectedException;
import zw.co.innbucks.loans.core.borrower.BorrowerProfile;
import zw.co.innbucks.loans.core.borrower.BorrowerSession;
import zw.co.innbucks.loans.core.borrower.BorrowerSessionService;
import zw.co.innbucks.loans.core.borrower.BorrowerSignInUnavailableException;
import zw.co.innbucks.loans.core.borrower.NotOnStaffRegisterException;
import zw.co.innbucks.loans.core.loan.CreditDecisionService;
import zw.co.innbucks.loans.web.BorrowerApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A borrower session and a staff session are kept apart by the REAL security chain ({@link ApiSecurityConfig}): the
 * borrower reaches the borrower endpoints and nothing else, even an endpoint that asks only for a valid token, whatever
 * roles the token carries; and the borrower endpoints refuse every staff session, SUPER_ADMIN's included. Sign-in itself
 * needs no token, and test assertions are not there unless switched on.
 */
@SpringJUnitWebConfig(BorrowerSessionFilterTest.Config.class)
class BorrowerSessionFilterTest {

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, CreditReasonCodeController.class,
            BorrowerController.class, BorrowerAuthController.class, TestAssertionController.class})
    static class Config {
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean CreditDecisionService creditDecisionService;
    @MockitoBean BorrowerSessionService sessionService;

    @Autowired WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        givenBorrowerToken("borrower", List.of("BORROWER"));
        // No token is minted like this; if one were, the session's type still decides, not its roles.
        givenBorrowerToken("borrower-admin", List.of("BORROWER", "SUPER_ADMIN"));
        givenStaffToken("credit", "CREDIT_MANAGER");
        givenStaffToken("admin", "SUPER_ADMIN");
        when(creditDecisionService.reasonCodes(any())).thenReturn(List.of());
        when(sessionService.profile(any(), any())).thenReturn(new BorrowerProfile("E1012", "Chipo Banda", "****6789",
                "Treasury", List.of("pin")));
    }

    private void givenBorrowerToken(String name, List<String> roles) {
        Jwt jwt = Jwt.withTokenValue(name + "-token").header("alg", "HS256")
                .subject("staff-member:2")
                .claim("preferred_username", "borrower:E1012")
                .claim("realm_access", Map.of("roles", roles))
                .claim(JwtService.TOKEN_USE_CLAIM, JwtService.BORROWER_TOKEN_USE)
                .claim(JwtService.STAFF_MEMBER_CLAIM, 2L)
                .claim(JwtService.MSISDN_CLAIM, "263773456789")
                .claim(JwtService.AUTHENTICATION_METHODS_CLAIM, List.of("pin"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900))
                .build();
        when(jwtDecoder.decode(name + "-token")).thenReturn(jwt);
    }

    private void givenStaffToken(String username, String role) {
        Jwt jwt = Jwt.withTokenValue(username + "-token").header("alg", "HS256")
                .subject(username + "-sub")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(jwtDecoder.decode(username + "-token")).thenReturn(jwt);
    }

    private static MockHttpServletRequestBuilder as(String name, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + name + "-token");
    }

    @Test
    @DisplayName("a borrower reaches its own profile, for the staff member and methods its session names")
    void borrowerReachesItsProfile() throws Exception {
        mvc.perform(as("borrower", get("/lending/v1/borrower/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employeeNumber").value("E1012"))
                .andExpect(jsonPath("$.data.maskedMsisdn").value("****6789"))
                .andExpect(jsonPath("$.data.signedInWith[0]").value("pin"));
        verify(sessionService).profile(2L, List.of("pin"));
    }

    @Test
    @DisplayName("a borrower session is refused everywhere else, even where only a valid token is asked for")
    void borrowerConfined() throws Exception {
        for (String borrower : List.of("borrower", "borrower-admin")) {
            mvc.perform(as(borrower, get("/lending/v1/credit-reason-codes")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("Forbidden - insufficient role"));
            mvc.perform(as(borrower, post("/lending/v1/loans").contentType(MediaType.APPLICATION_JSON).content("{}")))
                    .andExpect(status().isForbidden());
            mvc.perform(as(borrower, put("/lending/v1/me/password").contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(creditDecisionService);
    }

    @Test
    @DisplayName("the borrower endpoints refuse every staff session, SUPER_ADMIN's included, and need a token")
    void staffRefusedTheBorrowerEndpoints() throws Exception {
        for (String staff : List.of("credit", "admin")) {
            mvc.perform(as(staff, get("/lending/v1/borrower/me")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(as(staff, get("/lending/v1/credit-reason-codes"))).andExpect(status().isOk());
        }
        mvc.perform(get("/lending/v1/borrower/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("sign-in needs no token and answers the session")
    void exchange() throws Exception {
        when(sessionService.signIn(anyString())).thenReturn(new BorrowerSession("borrower-token", "Bearer", 900,
                "E1012", "Chipo Banda"));

        mvc.perform(post("/lending/v1/auth/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(BorrowerApiExamples.EXCHANGE_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.accessToken").value("borrower-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.employeeNumber").value("E1012"))
                .andExpect(jsonPath("$.data.fullName").value("Chipo Banda"));
    }

    @Test
    @DisplayName("each refusal of a sign-in is its own documented status and code")
    void exchangeRefusals() throws Exception {
        mvc.perform(post("/lending/v1/auth/exchange").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.assertion").value("assertion is required"));

        doThrow(new AssertionRejectedException("replayed jti")).when(sessionService).signIn(anyString());
        mvc.perform(post("/lending/v1/auth/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(BorrowerApiExamples.EXCHANGE_REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ASSERTION_REJECTED"))
                // The reason is for the log; the caller is told the same thing whatever it was.
                .andExpect(jsonPath("$.message").value("Assertion rejected - sign in to the SuperApp again"));

        doThrow(new NotOnStaffRegisterException()).when(sessionService).signIn(anyString());
        mvc.perform(post("/lending/v1/auth/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(BorrowerApiExamples.EXCHANGE_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ON_STAFF_REGISTER"))
                .andExpect(jsonPath("$.message").value(NotOnStaffRegisterException.MESSAGE));

        doThrow(new BorrowerSignInUnavailableException()).when(sessionService).signIn(anyString());
        mvc.perform(post("/lending/v1/auth/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(BorrowerApiExamples.EXCHANGE_REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("BORROWER_SIGN_IN_UNAVAILABLE"));
    }

    @Test
    @DisplayName("with test assertions off the test endpoint is not there at all, whatever is sent")
    void testAssertionsOff() throws Exception {
        mvc.perform(post("/lending/v1/auth/test-assertions").header("X-Api-Key", "anything")
                        .contentType(MediaType.APPLICATION_JSON).content(BorrowerApiExamples.TEST_ASSERTION_REQUEST))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/lending/v1/auth/test-assertions").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isNotFound());
    }
}
