package org.example.drive;

public record DriveWorkbookSnapshot(
        String fileName,
        String mimeType,
        String checksum,
        byte[] bytes
) {
}
