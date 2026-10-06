package dev.curlmock;

import java.nio.file.Path;

public record TransactionResult(String uuid, String endpointUrl, Path curlLog, String requestHeaders,
                                Path requestBody, String responseHeaders, Path responseBody,
                                int httpStatus, int curlExitCode, long elapsedMs, String error) {
    public boolean success() { return curlExitCode == 0 && httpStatus >= 200 && httpStatus < 400 && error.isEmpty(); }
}
