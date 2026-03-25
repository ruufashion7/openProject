package org.example.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Optional hCaptcha verification on login ({@code X-Captcha-Token} header = client response token).
 */
@Service
public class CaptchaVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(CaptchaVerificationService.class);

    private final boolean enabled;
    private final String secret;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CaptchaVerificationService(
            @Value("${security.captcha.provider:none}") String provider,
            @Value("${security.captcha.hcaptcha.secret:}") String secret) {
        this.secret = secret != null ? secret.trim() : "";
        this.enabled = "hcaptcha".equalsIgnoreCase(provider) && !this.secret.isEmpty();
        if ("hcaptcha".equalsIgnoreCase(provider) && this.secret.isEmpty()) {
            logger.warn("security.captcha.provider=hcaptcha but security.captcha.hcaptcha.secret is empty; captcha disabled.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean verify(String responseToken, String remoteIp) {
        if (!enabled) {
            return true;
        }
        if (responseToken == null || responseToken.isBlank()) {
            return false;
        }
        try {
            String form = "response=" + URLEncoder.encode(responseToken.trim(), StandardCharsets.UTF_8)
                    + "&secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            if (remoteIp != null && !remoteIp.isBlank()) {
                form += "&remoteip=" + URLEncoder.encode(remoteIp, StandardCharsets.UTF_8);
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://hcaptcha.com/siteverify"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                logger.warn("hCaptcha siteverify HTTP {}", res.statusCode());
                return false;
            }
            JsonNode root = objectMapper.readTree(res.body());
            return root.path("success").asBoolean(false);
        } catch (Exception e) {
            logger.warn("hCaptcha verification failed: {}", e.getMessage());
            return false;
        }
    }
}
