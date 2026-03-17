package org.example.upload;

import java.util.List;
import java.util.Map;

public record UploadedExcelSheet(
        String name,
        List<String> headers,
        List<Map<String, String>> rows
) {
}

