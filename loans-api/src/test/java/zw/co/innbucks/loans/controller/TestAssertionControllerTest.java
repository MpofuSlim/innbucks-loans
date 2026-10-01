package zw.co.innbucks.loans.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.borrower.BorrowerProperties;
import zw.co.innbucks.loans.core.borrower.MiddlewareAssertionVerifier;
import zw.co.innbucks.loans.core.borrower.TestAssertionSigner;
import zw.co.innbucks.loans.core.borrower.VerifiedAssertion;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.BorrowerApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staging's test assertions, switched on, behind the real security chain: no token needed, but the api key is checked
 * before anything else, so a caller without it learns nothing, not even what the body should hold. What is signed is
 * accepted by the real verifier.
 */
@SpringJUnitWebConfig(TestAssertionControllerTest.Config.class)
@TestPropertySource(properties = "loans.borrower.test-assertions.enabled=true")
class TestAssertionControllerTest {

    static final String API_KEY = "staging-test-assertions-key-0123456789abcdef";

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, TestAssertionController.class})
    static class Config {

        @Bean
        BorrowerProperties borrowerProperties() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            BorrowerProperties properties = new BorrowerProperties();
            properties.getTestAssertions().setEnabled(true);
            properties.getTestAssertions().setApiKey(API_KEY);
            properties.getTestAssertions().setPrivateKey(Base64.getEncoder().encodeToString(
                    generator.generateKeyPair().getPrivate().getEncoded()));
            return properties;
        }

        @Bean
        MarketTimeZone marketTimeZone() {
            return new MarketTimeZone("ZW");
        }

        @Bean
        TestAssertionSigner testAssertionSigner(BorrowerProperties properties, MarketTimeZone zone) {
            return new TestAssertionSigner(properties, zone);
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    @MockitoBean JwtDecoder jwtDecoder;

    @Autowired WebApplicationContext context;
    @Autowired BorrowerProperties properties;
    @Autowired MarketTimeZone marketTimeZone;
    @Autowired TestAssertionSigner signer;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    @DisplayName("with the api key: an assertion the real verifier accepts, for the phone and methods asked for")
    void signs() throws Exception {
        MvcResult result = mvc.perform(post("/lending/v1/auth/test-assertions").header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                { "phone": " +263773456789 ", "methods": ["fpt"] }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andReturn();
        String assertion = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString())
                .path("data").path("assertion").asString();

        VerifiedAssertion verified = new MiddlewareAssertionVerifier(properties, marketTimeZone, signer)
                .verify(assertion);
        assertThat(verified.phone()).isEqualTo("+263773456789");
        assertThat(verified.methods()).containsExactly("fpt");
    }

    @Test
    @DisplayName("methods left out are a PIN, as the middleware's ordinary sign-in")
    void defaultsToPin() throws Exception {
        mvc.perform(post("/lending/v1/auth/test-assertions").header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                { "phone": "0773456789" }"""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("without the right api key: 401, before the body is even looked at")
    void keyFirst() throws Exception {
        for (String key : new String[]{null, "", API_KEY + "x"}) {
            var request = post("/lending/v1/auth/test-assertions").contentType(MediaType.APPLICATION_JSON)
                    .content("{}");
            if (key != null) {
                request.header("X-Api-Key", key);
            }
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Invalid or missing api key"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        mvc.perform(post("/lending/v1/auth/test-assertions").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("with the key, a body that names no phone, or an odd method, is a field-level 400")
    void validatedAfterTheKey() throws Exception {
        mvc.perform(post("/lending/v1/auth/test-assertions").header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.phone").value("phone is required"));
        mvc.perform(post("/lending/v1/auth/test-assertions").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.phone").value("phone is required"));
        mvc.perform(post("/lending/v1/auth/test-assertions").header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                { "phone": "0773456789", "methods": ["PIN!"] }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.methods").value("a method is 1 to 16 lowercase letters"));
        mvc.perform(post("/lending/v1/auth/test-assertions").header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BorrowerApiExamples.TEST_ASSERTION_REQUEST))
                .andExpect(status().isOk());
    }
}
