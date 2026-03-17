package org.example.upload;

import java.util.List;

public record UploadedExcelFile(
        String originalFilename,
        List<UploadedExcelSheet> sheets
) {
}

