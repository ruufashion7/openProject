package org.example.upload;

import java.util.List;

public record UploadResponse(String status, String message, List<UploadFileInfo> files) {
}

