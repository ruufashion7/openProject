package org.example.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * On {@code prod} profile, disables Redis auto-configuration when no Redis URL is set so the app
 * does not connect to {@code localhost:6379} (e.g. Render). Set {@code SPRING_DATA_REDIS_URL} or
 * {@code spring.data.redis.url}, or {@code REDIS_URL} (e.g. some dashboards) to use managed Redis.
 */
public class DisableRedisUnlessConfiguredEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String EXCLUDE_CLASSES =
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!prodActive(environment)) {
            return;
        }
        if (redisUrlPresent(environment)) {
            return;
        }
        String existing = environment.getProperty("spring.autoconfigure.exclude", "");
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        splitCsv(existing, parts);
        splitCsv(EXCLUDE_CLASSES, parts);
        String merged = String.join(",", parts);
        Map<String, Object> props = new HashMap<>();
        props.put("spring.autoconfigure.exclude", merged);
        props.put("management.health.redis.enabled", "false");
        environment.getPropertySources().addFirst(new MapPropertySource("openproject-prod-no-redis", props));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static boolean prodActive(ConfigurableEnvironment env) {
        for (String p : env.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(p)) {
                return true;
            }
        }
        if (profileCsvContainsProd(env.getProperty("spring.profiles.active"))) {
            return true;
        }
        return profileCsvContainsProd(env.getProperty("SPRING_PROFILES_ACTIVE"));
    }

    private static boolean profileCsvContainsProd(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .anyMatch(s -> "prod".equalsIgnoreCase(s));
    }

    private static boolean redisUrlPresent(ConfigurableEnvironment env) {
        if (nonBlank(env.getProperty("SPRING_DATA_REDIS_URL"))) {
            return true;
        }
        if (nonBlank(env.getProperty("REDIS_URL"))) {
            return true;
        }
        return nonBlank(env.getProperty("spring.data.redis.url"));
    }

    private static boolean nonBlank(String u) {
        return u != null && !u.isBlank();
    }

    private static void splitCsv(String s, Set<String> out) {
        if (s == null || s.isBlank()) {
            return;
        }
        for (String p : s.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
    }
}
