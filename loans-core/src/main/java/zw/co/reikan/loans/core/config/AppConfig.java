package zw.co.reikan.loans.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Random;

@Configuration
public class AppConfig {

//    @Bean
//    public EhCacheManagerFactoryBean ehCacheManagerFactory() {
//        EhCacheManagerFactoryBean cacheManagerFactory = new EhCacheManagerFactoryBean();
//        cacheManagerFactory.setConfigLocation(new ClassPathResource("ehcache.xml"));
//        cacheManagerFactory.setShared(true);
//        return cacheManagerFactory;
//    }
//
//    @Bean
//    public CacheManager cacheManager() {
//        return new EhCacheCacheManager(ehCacheManagerFactory().getObject());
//    }

    @Bean
    Random random() {
        return new Random();
    }
}
