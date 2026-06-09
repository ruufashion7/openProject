package org.example.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * On {@code prod} profile, disables Redis auto-configuration when no usable Redis URL is set so the app
 * does not connect to {@code localhost:6379} (e.g. Render). Set {@code SPRING_DATA_REDIS_URL} or
 * {@code REDIS_URL} with a valid {@code redis://} / {@code rediss://} TCP URL (Upstash Connect tab).
 * Set {@code OPENPROJECT_REDIS_ENABLED=false} to ignore Redis env vars entirely.
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
        if (!redisExplicitlyEnabled(environment)) {
            disableRedis(environment, "openproject-prod-redis-disabled");
            return;
        }
        String url = resolveRedisUrl(environment);
        if (url == null) {
            disableRedis(environment, "openproject-prod-no-redis");
            return;
        }
        String host = parseRedisHost(url);
        if (host == null) {
            warn("Redis URL is not a valid redis:// or rediss:// TCP URL; Redis disabled. "
                    + "Use Upstash Connect → TCP (rediss://...), not the REST HTTPS URL.");
            disableRedis(environment, "openproject-prod-invalid-redis-url");
            return;
        }
        if (!hostResolvable(host)) {
            warn("Redis host does not resolve (" + host + "); Redis disabled. "
                    + "Update or remove SPRING_DATA_REDIS_URL / REDIS_URL in Render.");
            disableRedis(environment, "openproject-prod-unresolvable-redis-host");
            return;
        }
        disableRedisHealthOnly(environment);
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

    private static boolean redisExplicitlyEnabled(ConfigurableEnvironment env) {
        String flag = env.getProperty("OPENPROJECT_REDIS_ENABLED");
        if (flag == null || flag.isBlank()) {
            return true;
        }
        return Boolean.parseBoolean(flag.trim());
    }

    private static String resolveRedisUrl(ConfigurableEnvironment env) {
        String springUrl = env.getProperty("SPRING_DATA_REDIS_URL");
        if (nonBlank(springUrl)) {
            return springUrl.trim();
        }
        String redisUrl = env.getProperty("REDIS_URL");
        if (nonBlank(redisUrl)) {
            return redisUrl.trim();
        }
        String propertyUrl = env.getProperty("spring.data.redis.url");
        if (nonBlank(propertyUrl)) {
            return propertyUrl.trim();
        }
        return null;
    }

    private static String parseRedisHost(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"redis".equals(scheme) && !"rediss".equals(scheme)) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            return host;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean hostResolvable(String host) {
        try {
            InetAddress.getByName(host);
            return true;
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static void disableRedis(ConfigurableEnvironment environment, String sourceName) {
        String existing = environment.getProperty("spring.autoconfigure.exclude", "");
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        splitCsv(existing, parts);
        splitCsv(EXCLUDE_CLASSES, parts);
        Map<String, Object> props = new HashMap<>();
        props.put("spring.autoconfigure.exclude", String.join(",", parts));
        props.put("spring.data.redis.url", "");
        props.put("management.health.redis.enabled", "false");
        environment.getPropertySources().addFirst(new MapPropertySource(sourceName, props));
    }

    private static void disableRedisHealthOnly(ConfigurableEnvironment environment) {
        Map<String, Object> props = new HashMap<>();
        props.put("management.health.redis.enabled", "false");
        environment.getPropertySources().addFirst(new MapPropertySource("openproject-prod-redis-optional-health", props));
    }

    private static void warn(String message) {
        System.err.println("[openProject] " + message);
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
