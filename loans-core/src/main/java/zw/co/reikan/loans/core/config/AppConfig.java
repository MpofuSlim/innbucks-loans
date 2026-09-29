package zw.co.reikan.loans.core.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.Random;


@EnableCaching
@Configuration
public class AppConfig {

    /**
     * Draws the temporary passwords new users and forgot-password are sent. A plain {@code Random} is a
     * 48-bit linear congruential generator: a few outputs reveal its state, and from it every password
     * it will generate next.
     */
    @Bean
    Random random() {
        return new SecureRandom();
    }
}
