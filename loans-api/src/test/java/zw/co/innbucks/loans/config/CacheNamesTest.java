package zw.co.innbucks.loans.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring.cache.cache-names} in the packaged {@code application.yml} is a closed list (so each cache is bound,
 * and bound to the metrics, at startup): a cache the code names that is not on it would fail at its first call. Every
 * name in a {@code @Cacheable}, {@code @CachePut}, {@code @CacheEvict} or {@code @CacheConfig} anywhere in loans must be
 * listed, and nothing listed may be unused.
 */
class CacheNamesTest {

    @Test
    @DisplayName("the caches the code uses are exactly spring.cache.cache-names")
    void everyCacheTheCodeUsesIsConfigured() throws Exception {
        assertThat(cacheNamesInCode()).isNotEmpty().isEqualTo(configuredCacheNames());
    }

    private static Set<String> configuredCacheNames() throws Exception {
        for (PropertySource<?> document : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            Object names = document.getProperty("spring.cache.cache-names");
            if (names != null) {
                return Arrays.stream(names.toString().split(",")).map(String::strip)
                        .collect(Collectors.toCollection(TreeSet::new));
            }
        }
        throw new AssertionError("application.yml sets no spring.cache.cache-names");
    }

    private static Set<String> cacheNamesInCode() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        Set<String> names = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("zw.co.innbucks.loans")) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), CacheNamesTest.class.getClassLoader());
            collect(type, names);
            for (Method method : type.getDeclaredMethods()) {
                collect(method, names);
            }
        }
        return names;
    }

    private static void collect(AnnotatedElement element, Set<String> names) {
        MergedAnnotations annotations = MergedAnnotations.from(element);
        for (Class<? extends Annotation> type
                : List.of(Cacheable.class, CachePut.class, CacheEvict.class, CacheConfig.class)) {
            annotations.stream(type).map(MergedAnnotation::synthesize).forEach(annotation -> {
                String[] cacheNames = switch (annotation) {
                    case Cacheable a -> a.cacheNames();
                    case CachePut a -> a.cacheNames();
                    case CacheEvict a -> a.cacheNames();
                    case CacheConfig a -> a.cacheNames();
                    default -> new String[0];
                };
                names.addAll(Arrays.asList(cacheNames));
            });
        }
        // @Caching groups them.
        MergedAnnotation<Caching> caching = annotations.get(Caching.class);
        if (caching.isPresent()) {
            Caching c = caching.synthesize();
            Arrays.stream(c.cacheable()).forEach(a -> names.addAll(Arrays.asList(a.cacheNames())));
            Arrays.stream(c.put()).forEach(a -> names.addAll(Arrays.asList(a.cacheNames())));
            Arrays.stream(c.evict()).forEach(a -> names.addAll(Arrays.asList(a.cacheNames())));
        }
    }
}
