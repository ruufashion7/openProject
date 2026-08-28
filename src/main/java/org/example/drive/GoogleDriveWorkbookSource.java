package org.example.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Component
public class GoogleDriveWorkbookSource implements DriveWorkbookSource {

    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String GOOGLE_SHEET_MIME = "application/vnd.google-apps.spreadsheet";
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(45);

    private final DriveSyncProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final GoogleServiceAccountTokenProvider tokenProvider;

    @Autowired
    public GoogleDriveWorkbookSource(DriveSyncProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.tokenProvider = new GoogleServiceAccountTokenProvider(objectMapper, httpClient);
    }

    GoogleDriveWorkbookSource(
            DriveSyncProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            GoogleServiceAccountTokenProvider tokenProvider
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public DriveWorkbookSnapshot download() throws IOException {
        String fileId = properties.extractFileId();
        if (fileId.isBlank()) {
            throw new IOException("GOOGLE_DRIVE_FILE_ID is not set.");
        }
        String token = tokenProvider.accessToken(properties.serviceAccountJson());
        JsonNode meta = fetchMetadata(fileId, token);
        String name = meta.path("name").asText("drive-file.xlsx");
        String mime = meta.path("mimeType").asText("");
        String checksum = firstNonBlank(meta.path("md5Checksum").asText(""), meta.path("modifiedTime").asText(""), fileId);
        byte[] bytes = fetchBytes(fileId, mime, token);
        if (bytes.length == 0) {
            throw new IOException("Downloaded Drive file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Drive file is larger than 20 MB. Use a smaller payment-date workbook.");
        }
        return new DriveWorkbookSnapshot(name, mime, checksum, bytes);
    }

    @Override
    public DriveWorkbookSnapshot upload(DriveWorkbookSnapshot source, PaymentDateWorkbookWriter.Result written) throws IOException {
        String fileId = properties.extractFileId();
        if (fileId.isBlank()) {
            throw new IOException("GOOGLE_DRIVE_FILE_ID is not set.");
        }
        if (written == null || !written.hasChanges()) {
            throw new IOException("No workbook changes to upload.");
        }
        String token = tokenProvider.accessToken(properties.serviceAccountJson());
        String mime = source == null ? "" : source.mimeType();
        if (GOOGLE_SHEET_MIME.equals(mime)) {
            uploadGoogleSheetChanges(fileId, token, written);
        } else {
            uploadXlsx(fileId, token, written.bytes());
        }
        JsonNode updatedMeta = fetchMetadata(fileId, token);
        String name = updatedMeta.path("name").asText(source == null ? "drive-file" : source.fileName());
        String updatedMime = updatedMeta.path("mimeType").asText(mime);
        String checksum = firstNonBlank(
                updatedMeta.path("md5Checksum").asText(""),
                updatedMeta.path("modifiedTime").asText(""),
                fileId
        );
        return new DriveWorkbookSnapshot(name, updatedMime, checksum, written.bytes());
    }

    private void uploadXlsx(String fileId, String token, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Cannot upload an empty workbook.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Drive file is larger than 20 MB.");
        }
        String encodedId = URLEncoder.encode(fileId, StandardCharsets.UTF_8);
        String url = "https://www.googleapis.com/upload/drive/v3/files/" + encodedId
                + "?uploadType=media&supportsAllDrives=true";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", XLSX_MIME)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 403) {
            throw new IOException("Drive write denied. Share the file with the service account as Editor.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Drive upload HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
    }

    private void uploadGoogleSheetChanges(String spreadsheetId, String token, PaymentDateWorkbookWriter.Result written)
            throws IOException {
        if ((written.cellUpdates() == null || written.cellUpdates().isEmpty())
                && (written.rowDeletes() == null || written.rowDeletes().isEmpty())) {
            throw new IOException("No Google Sheet changes to upload.");
        }
        if (written.cellUpdates() != null && !written.cellUpdates().isEmpty()) {
            ensureGoogleSheetGridFits(spreadsheetId, token, written.sheetName(), written.cellUpdates());
        }
        if (written.rowDeletes() != null && !written.rowDeletes().isEmpty()) {
            deleteGoogleSheetRows(spreadsheetId, token, written.sheetName(), written.rowDeletes());
        }
        if (written.cellUpdates() != null && !written.cellUpdates().isEmpty()) {
            uploadGoogleSheetCells(spreadsheetId, token, written);
        }
    }

    private void ensureGoogleSheetGridFits(
            String spreadsheetId,
            String token,
            String sheetName,
            List<SheetCellUpdate> cellUpdates
    ) throws IOException {
        GoogleSheetTab tab = fetchSheetTab(spreadsheetId, token, sheetName);
        int requiredRows = GoogleSheetGridRequirements.requiredRowCount(cellUpdates);
        int requiredCols = GoogleSheetGridRequirements.requiredColumnCount(cellUpdates);
        int targetRows = Math.max(tab.rowCount(), requiredRows);
        int targetCols = Math.max(tab.columnCount(), requiredCols);
        if (targetRows <= tab.rowCount() && targetCols <= tab.columnCount()) {
            return;
        }
        updateGoogleSheetGridSize(spreadsheetId, token, tab.sheetId(), targetRows, targetCols);
    }

    private void updateGoogleSheetGridSize(
            String spreadsheetId,
            String token,
            int sheetId,
            int rowCount,
            int columnCount
    ) throws IOException {
        ObjectNode grid = objectMapper.createObjectNode();
        grid.put("rowCount", rowCount);
        grid.put("columnCount", columnCount);
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("sheetId", sheetId);
        properties.set("gridProperties", grid);
        ObjectNode update = objectMapper.createObjectNode();
        update.set("properties", properties);
        update.put("fields", "gridProperties.rowCount,gridProperties.columnCount");
        ObjectNode request = objectMapper.createObjectNode();
        request.set("updateSheetProperties", update);
        ArrayNode requests = objectMapper.createArrayNode();
        requests.add(request);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("requests", requests);

        String url = "https://sheets.googleapis.com/v4/spreadsheets/"
                + URLEncoder.encode(spreadsheetId, StandardCharsets.UTF_8)
                + ":batchUpdate";
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = send(httpRequest);
        if (response.statusCode() == 403) {
            throw new IOException(sheetsWriteErrorMessage(response.body()));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Google Sheets grid resize HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
    }

    private void deleteGoogleSheetRows(
            String spreadsheetId,
            String token,
            String sheetName,
            List<SheetRowDelete> rowDeletes
    ) throws IOException {
        int sheetId = fetchSheetTab(spreadsheetId, token, sheetName).sheetId();
        ArrayNode requests = objectMapper.createArrayNode();
        rowDeletes.stream()
                .map(SheetRowDelete::rowIndex)
                .sorted(Comparator.reverseOrder())
                .forEach(rowIndex -> {
                    ObjectNode delete = objectMapper.createObjectNode();
                    ObjectNode range = objectMapper.createObjectNode();
                    range.put("sheetId", sheetId);
                    range.put("dimension", "ROWS");
                    range.put("startIndex", rowIndex);
                    range.put("endIndex", rowIndex + 1);
                    delete.set("range", range);
                    ObjectNode request = objectMapper.createObjectNode();
                    request.set("deleteDimension", delete);
                    requests.add(request);
                });

        ObjectNode body = objectMapper.createObjectNode();
        body.set("requests", requests);
        String url = "https://sheets.googleapis.com/v4/spreadsheets/"
                + URLEncoder.encode(spreadsheetId, StandardCharsets.UTF_8)
                + ":batchUpdate";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 403) {
            throw new IOException(sheetsWriteErrorMessage(response.body()));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Google Sheets row delete HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
    }

    private GoogleSheetTab fetchSheetTab(String spreadsheetId, String token, String sheetName) throws IOException {
        String fields = URLEncoder.encode(
                "sheets(properties(sheetId,title,gridProperties(rowCount,columnCount)))",
                StandardCharsets.UTF_8);
        String url = "https://sheets.googleapis.com/v4/spreadsheets/"
                + URLEncoder.encode(spreadsheetId, StandardCharsets.UTF_8)
                + "?fields=" + fields;
        HttpResponse<String> response = send(GET(url, token));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Google Sheets metadata HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonNode sheets = objectMapper.readTree(response.body()).path("sheets");
        if (!sheets.isArray()) {
            throw new IOException("Google Sheet has no tabs.");
        }
        JsonNode matched = null;
        for (JsonNode sheet : sheets) {
            String title = sheet.path("properties").path("title").asText("");
            if (title.equals(sheetName)) {
                matched = sheet;
                break;
            }
        }
        if (matched == null && sheets.size() > 0) {
            matched = sheets.get(0);
        }
        if (matched == null) {
            throw new IOException("Google Sheet tab \"" + sheetName + "\" was not found.");
        }
        JsonNode properties = matched.path("properties");
        JsonNode grid = properties.path("gridProperties");
        int rowCount = grid.path("rowCount").asInt(1000);
        int columnCount = grid.path("columnCount").asInt(26);
        return new GoogleSheetTab(properties.path("sheetId").asInt(), rowCount, columnCount);
    }

    private record GoogleSheetTab(int sheetId, int rowCount, int columnCount) {
    }

    private void uploadGoogleSheetCells(String spreadsheetId, String token, PaymentDateWorkbookWriter.Result written)
            throws IOException {
        if (written.cellUpdates() == null || written.cellUpdates().isEmpty()) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("valueInputOption", "USER_ENTERED");
        ArrayNode data = objectMapper.createArrayNode();
        for (SheetCellUpdate update : written.cellUpdates()) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("range", GoogleSheetsCellRange.a1(written.sheetName(), update.rowIndex(), update.colIndex()));
            ArrayNode row = objectMapper.createArrayNode();
            row.add(update.value() == null ? "" : update.value());
            ArrayNode values = objectMapper.createArrayNode();
            values.add(row);
            entry.set("values", values);
            data.add(entry);
        }
        body.set("data", data);

        String url = "https://sheets.googleapis.com/v4/spreadsheets/"
                + URLEncoder.encode(spreadsheetId, StandardCharsets.UTF_8)
                + "/values:batchUpdate";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 403) {
            throw new IOException(sheetsWriteErrorMessage(response.body()));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Google Sheets HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
    }

    private String sheetsWriteErrorMessage(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String message = error.path("message").asText("");
            if (message.contains("Sheets API has not been used") || message.contains("it is disabled")) {
                return "Google Sheets API is not enabled for this Google Cloud project. "
                        + "Open Google Cloud Console → APIs & Services → Library → enable Google Sheets API, "
                        + "wait 1–2 minutes, restart the backend, then sync again.";
            }
            if (!message.isBlank()) {
                if (message.contains("exceeds grid limits")) {
                    return "Google Sheet is too small for the payment-date columns. "
                            + "The app tried to expand the sheet automatically but Google returned: "
                            + truncate(message);
                }
                return "Google Sheets write denied: " + truncate(message);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "Google Sheets write denied. Share the sheet with the service account as Editor and enable Google Sheets API.";
    }

    private JsonNode fetchMetadata(String fileId, String token) throws IOException {
        String url = "https://www.googleapis.com/drive/v3/files/"
                + URLEncoder.encode(fileId, StandardCharsets.UTF_8)
                + "?fields=" + URLEncoder.encode("id,name,mimeType,md5Checksum,modifiedTime", StandardCharsets.UTF_8)
                + "&supportsAllDrives=true";
        HttpResponse<String> response = send(GET(url, token));
        if (response.statusCode() == 404) {
            throw new IOException("Drive file not found. Share the file with the service account email and check GOOGLE_DRIVE_FILE_ID.");
        }
        if (response.statusCode() == 403) {
            throw new IOException("Drive access denied. Share the .xlsx with the service account as Editor.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Drive metadata HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        return objectMapper.readTree(response.body());
    }

    private byte[] fetchBytes(String fileId, String mime, String token) throws IOException {
        String encodedId = URLEncoder.encode(fileId, StandardCharsets.UTF_8);
        String url;
        if (GOOGLE_SHEET_MIME.equals(mime)) {
            url = "https://www.googleapis.com/drive/v3/files/" + encodedId
                    + "/export?mimeType=" + URLEncoder.encode(XLSX_MIME, StandardCharsets.UTF_8);
        } else {
            url = "https://www.googleapis.com/drive/v3/files/" + encodedId + "?alt=media&supportsAllDrives=true";
        }
        HttpResponse<byte[]> response = sendBytes(GET(url, token));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() == null ? "" : new String(response.body(), StandardCharsets.UTF_8);
            throw new IOException("Drive download HTTP " + response.statusCode() + ": " + truncate(body));
        }
        return response.body() == null ? new byte[0] : response.body();
    }

    private HttpRequest GET(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Google Drive.", ex);
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading Google Drive file.", ex);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240);
    }
}
