package zw.co.reikan.loans.core.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;


@EnableCaching
@Configuration
public class AppConfig {

    @Bean
    Random random() {
        return new Random();
    }
}
