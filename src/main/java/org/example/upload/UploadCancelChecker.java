package org.example.upload;

@FunctionalInterface
public interface UploadCancelChecker {
    /**
     * Invoked from long-running parse paths; must throw {@link UploadCancellationException} when cancel is requested.
     */
    void checkCancelled();

    UploadCancelChecker NONE = () -> {
    };
}
