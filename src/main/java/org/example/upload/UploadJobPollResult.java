package org.example.upload;

/**
 * Outcome of polling {@link UploadJobService#pollJob(String)}.
 */
public record UploadJobPollResult(Type type, UploadJobStatusResponse body) {
    public enum Type {
        NOT_FOUND,
        OK
    }

    public static UploadJobPollResult notFound() {
        return new UploadJobPollResult(Type.NOT_FOUND, null);
    }

    public static UploadJobPollResult ok(UploadJobStatusResponse body) {
        return new UploadJobPollResult(Type.OK, body);
    }
}
