package zw.co.innbucks.loans.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import zw.co.innbucks.loans.core.getmore.simulator.GetMoreSimulatorActions;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorApiKey;
import zw.co.innbucks.loans.security.ApiSecurityConfig;
import zw.co.innbucks.loans.web.GetMoreApiExamples;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Outside SIMULATED the GetMore simulator's test actions do not exist: no controller, no actions service, no key and no
 * chain of their own, so every one of their paths is the plain 404 any unknown path gets, with or without a key, a
 * token or a CSRF token. A 401 or a 403 there would tell a caller on a LIVE or OFF host (production among them) that
 * something lives at that path. Runs the REAL {@link ApiSecurityConfig} with the simulator's classes imported, so their
 * own conditions are what keeps them out.
 */
@SpringJUnitWebConfig(GetMoreSimulatorAbsentTest.Config.class)
class GetMoreSimulatorAbsentTest {

    private static final String BASE = "/lending/v1/getmore-simulator";

    @Configuration
    @EnableWebMvc
    @Import({ApiSecurityConfig.class, GlobalExceptionHandler.class, GetMoreSimulatorController.class,
            GetMoreSimulatorActions.class, SimulatorApiKey.class})
    static class Config {
    }

    @MockitoBean JwtDecoder jwtDecoder;

    @Autowired WebApplicationContext context;

    private static List<MockHttpServletRequestBuilder> everySimulatorCall() {
        return List.of(
                post(BASE + "/sales").contentType(MediaType.APPLICATION_JSON).content(GetMoreApiExamples.SALE_REQUEST),
                post(BASE + "/sales/SIM000000013/invoices"),
                post(BASE + "/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content(GetMoreApiExamples.REFUND_REQUEST),
                post(BASE + "/coupon-expiries").contentType(MediaType.APPLICATION_JSON)
                        .content(GetMoreApiExamples.EXPIRY_REQUEST),
                post(BASE + "/coupon-lookups").contentType(MediaType.APPLICATION_JSON)
                        .content(GetMoreApiExamples.LOOKUP_REQUEST),
                post(BASE + "/faults").contentType(MediaType.APPLICATION_JSON)
                        .content(GetMoreApiExamples.FAULT_REQUEST),
                delete(BASE + "/faults"),
                post(BASE + "/anything-else"));
    }

    static void assertAbsent(WebApplicationContext context) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        assertThat(context.getBeanNamesForType(GetMoreSimulatorController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(GetMoreSimulatorActions.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SimulatorApiKey.class)).isEmpty();
        assertThat(context.containsBean("simulatorSecurityFilterChain")).isFalse();
        for (MockHttpServletRequestBuilder request : everySimulatorCall()) {
            mvc.perform(request).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Not found"));
        }
        for (MockHttpServletRequestBuilder request : everySimulatorCall()) {
            mvc.perform(request.header("X-Api-Key", "staging-getmore-simulator-key-0123456789abcdef"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("with GETMORE_MODE unset (OFF): no simulator beans, and every simulator path a plain 404, never a"
            + " 401 or 403")
    void off() throws Exception {
        assertAbsent(context);
    }

    @Nested
    @TestPropertySource(properties = "loans.getmore.mode=LIVE")
    class Live {

        @Autowired WebApplicationContext liveContext;

        @Test
        @DisplayName("LIVE (production's mode): the same plain 404 on every simulator path")
        void live() throws Exception {
            assertThat(liveContext.getEnvironment().getProperty("loans.getmore.mode")).isEqualTo("LIVE");
            assertAbsent(liveContext);
        }
    }
}
