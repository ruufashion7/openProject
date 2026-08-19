package org.example.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manual diagnostic — run: ./mvnw -Dtest=GoogleSheetsWriteDiagnosticTest test
 */
@Disabled("Manual Google API diagnostic — run locally when debugging Sheets 403")
class GoogleSheetsWriteDiagnosticTest {

    @Test
    void diagnoseSheetsWriteAccess() throws Exception {
        Path credPath = Path.of("google-service-account.json");
        if (!Files.isRegularFile(credPath)) {
            System.out.println("SKIP: google-service-account.json not found");
            return;
        }
        String json = Files.readString(credPath);
        String fileId = System.getenv().getOrDefault("GOOGLE_DRIVE_FILE_ID", "1BI3McIZmgu2jr5Pkhcs_qNlA8JYSfDkdGdHx6Apzv5s");

        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        GoogleServiceAccountTokenProvider tokens = new GoogleServiceAccountTokenProvider(mapper, http);
        String token = tokens.accessToken(json);

        HttpRequest meta = HttpRequest.newBuilder(URI.create(
                "https://www.googleapis.com/drive/v3/files/" + fileId + "?fields=name,mimeType,capabilities&supportsAllDrives=true"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> metaRes = http.send(meta, HttpResponse.BodyHandlers.ofString());
        System.out.println("Drive metadata HTTP " + metaRes.statusCode() + ": " + metaRes.body());

        HttpRequest getSheet = HttpRequest.newBuilder(URI.create(
                "https://sheets.googleapis.com/v4/spreadsheets/" + fileId + "?fields=spreadsheetId,properties.title"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> sheetRes = http.send(getSheet, HttpResponse.BodyHandlers.ofString());
        System.out.println("Sheets GET HTTP " + sheetRes.statusCode() + ": " + sheetRes.body());

        String body = """
                {"valueInputOption":"USER_ENTERED","data":[{"range":"'Sheet1'!Z999","values":[["diag"]]}]}
                """;
        HttpRequest write = HttpRequest.newBuilder(URI.create(
                "https://sheets.googleapis.com/v4/spreadsheets/" + fileId + "/values:batchUpdate"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> writeRes = http.send(write, HttpResponse.BodyHandlers.ofString());
        System.out.println("Sheets write HTTP " + writeRes.statusCode() + ": " + writeRes.body());
    }
}
