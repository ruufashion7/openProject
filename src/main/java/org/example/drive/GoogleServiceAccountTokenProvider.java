package org.example.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service-account OAuth access token for Drive API (JWT bearer grant).
 */
public final class GoogleServiceAccountTokenProvider {

    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
    private static final String SPREADSHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private static final String OAUTH_SCOPES = DRIVE_SCOPE + " " + SPREADSHEETS_SCOPE;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(25);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public GoogleServiceAccountTokenProvider(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public String accessToken(String serviceAccountJsonOrPath) throws IOException {
        CachedToken current = cached.get();
        Instant now = Instant.now();
        if (current != null && now.isBefore(current.expiresAt().minusSeconds(60))) {
            return current.value();
        }
        Account account = parseAccount(serviceAccountJsonOrPath);
        String jwt = signJwt(account, now);
        String token = requestAccessToken(account.tokenUri(), jwt);
        cached.set(new CachedToken(token, now.plus(Duration.ofMinutes(50))));
        return token;
    }

    Account parseAccount(String raw) throws IOException {
        String json = resolveJson(raw);
        JsonNode node = objectMapper.readTree(json);
        String email = text(node, "client_email");
        String privateKeyPem = text(node, "private_key");
        String tokenUri = node.path("token_uri").asText("https://oauth2.googleapis.com/token");
        if (email.isBlank() || privateKeyPem.isBlank()) {
            throw new IOException("Service account JSON must include client_email and private_key.");
        }
        return new Account(email, privateKeyPem, tokenUri);
    }

    String resolveJson(String raw) throws IOException {
        String value = unwrapCredentialValue(raw);
        if (value.isBlank()) {
            throw new IOException("GOOGLE_SERVICE_ACCOUNT_JSON is empty.");
        }
        if (looksLikeJsonObject(value)) {
            return value;
        }
        Path path = resolveCredentialFile(value);
        if (path != null && Files.isRegularFile(path)) {
            String fromFile = unwrapCredentialValue(Files.readString(path));
            if (looksLikeJsonObject(fromFile)) {
                return fromFile;
            }
            throw new IOException("GOOGLE_SERVICE_ACCOUNT_JSON file does not contain a JSON object: " + path);
        }
        Optional<String> decoded = decodeBase64Json(value);
        if (decoded.isPresent()) {
            return decoded.get();
        }
        throw new IOException(
                "GOOGLE_SERVICE_ACCOUNT_JSON must be raw JSON, base64 JSON, or a file path. "
                        + hint(value)
                        + (looksLikePath(value) && path != null && !Files.isRegularFile(path)
                        ? " No file at " + path + "."
                        : "")
        );
    }

    private static String unwrapCredentialValue(String raw) {
        if (raw == null) {
            return "";
        }
        String value = stripBom(raw).trim();
        for (int i = 0; i < 2; i++) {
            if (value.length() < 2) {
                break;
            }
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'') || (first == '`' && last == '`')) {
                value = stripBom(value.substring(1, value.length() - 1)).trim();
                continue;
            }
            break;
        }
        if (value.startsWith("%7B") || value.startsWith("%7b")) {
            value = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8).trim();
        }
        return value;
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value == null ? "" : value;
    }

    private static boolean looksLikeJsonObject(String value) {
        return value.startsWith("{");
    }

    private static boolean looksLikePath(String value) {
        return value.startsWith("file:")
                || value.startsWith("~")
                || value.startsWith("/")
                || value.endsWith(".json")
                || value.contains("/")
                || value.contains("\\")
                || (value.length() > 2 && value.charAt(1) == ':' && Character.isLetter(value.charAt(0)));
    }

    private static Path resolveCredentialFile(String value) {
        if (value.startsWith("file:")) {
            try {
                return Path.of(URI.create(value));
            } catch (IllegalArgumentException ex) {
                String withoutScheme = value.substring("file:".length());
                while (withoutScheme.startsWith("//")) {
                    withoutScheme = withoutScheme.substring(1);
                }
                return Path.of(withoutScheme);
            }
        }
        String expanded = expandHome(value);
        Path path = Path.of(expanded);
        if (Files.isRegularFile(path)) {
            return path;
        }
        if (!path.isAbsolute()) {
            Path fromCwd = Path.of(System.getProperty("user.dir", ".")).resolve(expanded).normalize();
            if (Files.isRegularFile(fromCwd)) {
                return fromCwd;
            }
            return fromCwd;
        }
        return path;
    }

    private static String expandHome(String value) {
        if ("~".equals(value)) {
            return System.getProperty("user.home", value);
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return System.getProperty("user.home", "") + value.substring(1);
        }
        return value;
    }

    private static Optional<String> decodeBase64Json(String value) {
        String compact = value.replaceAll("\\s", "");
        if (compact.isEmpty()) {
            return Optional.empty();
        }
        byte[] decoded = tryBase64(compact);
        if (decoded == null) {
            return Optional.empty();
        }
        String asText = unwrapCredentialValue(new String(decoded, StandardCharsets.UTF_8));
        return looksLikeJsonObject(asText) ? Optional.of(asText) : Optional.empty();
    }

    private static byte[] tryBase64(String compact) {
        String padded = padBase64(compact);
        try {
            return Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException ignored) {
            // try URL-safe next
        }
        try {
            return Base64.getUrlDecoder().decode(padded);
        } catch (IllegalArgumentException ignored) {
            // try MIME next
        }
        try {
            return Base64.getMimeDecoder().decode(compact);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        return value + "=".repeat(4 - remainder);
    }

    private static String hint(String value) {
        String preview = value.replaceAll("\\s+", " ").trim();
        if (preview.length() > 48) {
            preview = preview.substring(0, 48) + "…";
        }
        return "Received " + value.length() + " chars starting with '" + preview + "'.";
    }

    private String signJwt(Account account, Instant now) throws IOException {
        try {
            PrivateKey key = parsePkcs8(account.privateKeyPem());
            Instant exp = now.plus(Duration.ofMinutes(55));
            return Jwts.builder()
                    .issuer(account.clientEmail())
                    .subject(account.clientEmail())
                    .claim("aud", account.tokenUri())
                    .claim("scope", OAUTH_SCOPES)
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(exp))
                    .signWith(key, Jwts.SIG.RS256)
                    .compact();
        } catch (Exception ex) {
            throw new IOException("Failed to sign Google service-account JWT.", ex);
        }
    }

    private String requestAccessToken(String tokenUri, String jwt) throws IOException {
        String body = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Google token endpoint returned HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }
            JsonNode node = objectMapper.readTree(response.body());
            String token = node.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IOException("Google token response had no access_token.");
            }
            return token;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while requesting Google access token.", ex);
        }
    }

    private static PrivateKey parsePkcs8(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }

    record Account(String clientEmail, String privateKeyPem, String tokenUri) {
    }

    private record CachedToken(String value, Instant expiresAt) {
    }
}
