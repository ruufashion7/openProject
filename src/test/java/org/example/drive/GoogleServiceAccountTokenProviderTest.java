package org.example.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleServiceAccountTokenProviderTest {

    private static final String JSON = "{\"client_email\":\"bot@x.iam.gserviceaccount.com\",\"private_key\":\"test-key\"}";

    private final GoogleServiceAccountTokenProvider provider =
            new GoogleServiceAccountTokenProvider(new ObjectMapper(), HttpClient.newHttpClient());

    @Test
    void parseAccount_rawJson() throws Exception {
        GoogleServiceAccountTokenProvider.Account account = provider.parseAccount(JSON);
        assertEquals("bot@x.iam.gserviceaccount.com", account.clientEmail());
        assertEquals("test-key", account.privateKeyPem());
    }

    @Test
    void parseAccount_quotedJsonAndBom() throws Exception {
        GoogleServiceAccountTokenProvider.Account quoted = provider.parseAccount("\"" + JSON + "\"");
        assertEquals("bot@x.iam.gserviceaccount.com", quoted.clientEmail());

        GoogleServiceAccountTokenProvider.Account bom = provider.parseAccount("\uFEFF" + JSON);
        assertEquals("bot@x.iam.gserviceaccount.com", bom.clientEmail());
    }

    @Test
    void parseAccount_filePathAndQuotedPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("google-service-account.json");
        Files.writeString(file, JSON);

        GoogleServiceAccountTokenProvider.Account fromPath = provider.parseAccount(file.toString());
        assertEquals("bot@x.iam.gserviceaccount.com", fromPath.clientEmail());

        GoogleServiceAccountTokenProvider.Account quoted = provider.parseAccount("\"" + file + "\"");
        assertEquals("bot@x.iam.gserviceaccount.com", quoted.clientEmail());

        GoogleServiceAccountTokenProvider.Account fileUri = provider.parseAccount(file.toUri().toString());
        assertEquals("bot@x.iam.gserviceaccount.com", fileUri.clientEmail());
    }

    @Test
    void parseAccount_standardAndUrlSafeBase64() throws Exception {
        String standard = Base64.getEncoder().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));
        assertEquals("bot@x.iam.gserviceaccount.com", provider.parseAccount(standard).clientEmail());

        String urlSafe = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.getBytes(StandardCharsets.UTF_8));
        assertEquals("bot@x.iam.gserviceaccount.com", provider.parseAccount(urlSafe).clientEmail());
    }

    @Test
    void parseAccount_invalidValue_mentionsFormats() {
        IOException ex = assertThrows(IOException.class, () -> provider.parseAccount("not-json-or-path"));
        assertTrue(ex.getMessage().contains("raw JSON, base64 JSON, or a file path"));
        assertTrue(ex.getMessage().contains("not-json-or-path"));
    }
}
