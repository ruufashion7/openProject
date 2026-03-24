package org.example.upload;

/**
 * Thrown when a cooperative cancel is observed during Excel parse (before DB writes).
 */
public final class UploadCancellationException extends RuntimeException {
    public UploadCancellationException() {
        super("Upload cancelled");
    }
}
