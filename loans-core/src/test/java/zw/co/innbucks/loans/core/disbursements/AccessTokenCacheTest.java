package zw.co.innbucks.loans.core.disbursements;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.ndasenda.NdasendaAuthResponse;
import zw.co.innbucks.loans.core.ndasenda.NdasendaAuthService;
import zw.co.innbucks.loans.core.ndasenda.NdasendaParameters;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The InnBucks and Ndasenda access tokens through Spring's real cache proxy (the contract tests build these services
 * by hand, where {@code @Cacheable} is inert). After a 401 the caller refreshes and then replays, building its headers
 * from {@code getAccessToken()} again: that replay must use the token the refresh fetched, so a 401 costs ONE login.
 * {@code refreshToken()} used to call {@code getAccessToken()} on itself, past the proxy, so the new token was never
 * cached and the replay logged in a second time.
 */
class AccessTokenCacheTest {

    private static final AtomicInteger INNBUCKS_LOGINS = new AtomicInteger();
    private static final AtomicInteger NDASENDA_LOGINS = new AtomicInteger();
    private static volatile boolean failLogins;

    @Configuration
    @EnableCaching
    @Import({InnbucksAuthService.class, NdasendaAuthService.class})
    static class Config {

        @Bean
        CaffeineCacheManager cacheManager() {
            return new CaffeineCacheManager();
        }

        @Bean
        InnbucksParameters innbucksParameters() {
            InnbucksParameters parameters = new InnbucksParameters();
            parameters.setAuthEndpoint("http://innbucks.test/auth/third-party");
            return parameters;
        }

        @Bean
        NdasendaParameters ndasendaParameters() {
            NdasendaParameters parameters = new NdasendaParameters();
            parameters.setAuthEndpoint("http://ndasenda.test/connect/token");
            return parameters;
        }

        /** Each login answers a new token: innbucks-1, innbucks-2, ... */
        @Bean
        RestTemplate restTemplate() {
            RestTemplate restTemplate = mock(RestTemplate.class);
            when(restTemplate.exchange(eq("http://innbucks.test/auth/third-party"), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(InnbucksAuthResponse.class))).thenAnswer(i -> {
                if (failLogins) {
                    throw new IllegalStateException("login refused");
                }
                InnbucksAuthResponse response = new InnbucksAuthResponse();
                response.setAccessToken("innbucks-" + INNBUCKS_LOGINS.incrementAndGet());
                return ResponseEntity.ok(response);
            });
            when(restTemplate.exchange(eq("http://ndasenda.test/connect/token"), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(NdasendaAuthResponse.class))).thenAnswer(i -> {
                if (failLogins) {
                    throw new IllegalStateException("login refused");
                }
                NdasendaAuthResponse response = new NdasendaAuthResponse();
                response.setAccessToken("ndasenda-" + NDASENDA_LOGINS.incrementAndGet());
                return ResponseEntity.ok(response);
            });
            return restTemplate;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withInitializer(context -> {
                INNBUCKS_LOGINS.set(0);
                NDASENDA_LOGINS.set(0);
                failLogins = false;
            });

    @Test
    @DisplayName("InnBucks: cached; a refresh logs in once and the replay uses that token")
    void innbucksRefreshIsCached() {
        runner.run(context -> {
            InnbucksAuthService auth = context.getBean(InnbucksAuthService.class);

            assertThat(auth.getAccessToken()).isEqualTo("innbucks-1");
            assertThat(auth.getAccessToken()).isEqualTo("innbucks-1");
            assertThat(auth.refreshToken()).isEqualTo("innbucks-2");
            assertThat(auth.getAccessToken()).as("the replay after the 401").isEqualTo("innbucks-2");
            assertThat(INNBUCKS_LOGINS).hasValue(2);
        });
    }

    @Test
    @DisplayName("Ndasenda: cached; a refresh logs in once and the replay uses that token")
    void ndasendaRefreshIsCached() {
        runner.run(context -> {
            NdasendaAuthService auth = context.getBean(NdasendaAuthService.class);

            assertThat(auth.getAccessToken()).isEqualTo("ndasenda-1");
            assertThat(auth.refreshToken()).isEqualTo("ndasenda-2");
            assertThat(auth.getAccessToken()).isEqualTo("ndasenda-2");
            assertThat(NDASENDA_LOGINS).hasValue(2);
        });
    }

    @Test
    @DisplayName("a refresh whose login fails leaves NO token cached: the refused one is gone, the next call logs in")
    void failedRefreshCachesNothing() {
        runner.run(context -> {
            InnbucksAuthService innbucks = context.getBean(InnbucksAuthService.class);
            NdasendaAuthService ndasenda = context.getBean(NdasendaAuthService.class);
            assertThat(innbucks.getAccessToken()).isEqualTo("innbucks-1");
            assertThat(ndasenda.getAccessToken()).isEqualTo("ndasenda-1");

            failLogins = true;
            assertThatThrownBy(innbucks::refreshToken).hasMessage("login refused");
            assertThatThrownBy(ndasenda::refreshToken).hasMessage("login refused");
            failLogins = false;

            assertThat(innbucks.getAccessToken()).as("never the refused token").isEqualTo("innbucks-2");
            assertThat(ndasenda.getAccessToken()).isEqualTo("ndasenda-2");
        });
    }
}
