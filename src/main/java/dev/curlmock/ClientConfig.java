package dev.curlmock;

import java.util.List;
import java.util.ArrayList;
import java.net.URI;

public record ClientConfig(String endpointUrl, String method, String curlExecutable,
                           Integer connectTimeoutSeconds, Integer requestTimeoutSeconds,
                           String outputDirectory, List<PayloadType> payloadTypes) {
    public static ClientConfig sample() {
        var types = new ArrayList<PayloadType>();
        for (var ct : ContentType.values()) for (var te : TransferEncoding.values()) for (var ps : List.of(PayloadSize.SM, PayloadSize.CM, PayloadSize.LG)) {
            types.add(new PayloadType(ct, te, ps));
        }
        return new ClientConfig("http://localhost:8080", "POST", "curl", 3, 3, "results", List.copyOf(types));
    }
    public void validate() {
        if (endpointUrl == null || endpointUrl.isBlank()) throw new IllegalArgumentException("endpointUrl is required");
        URI uri = URI.create(endpointUrl);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("endpointUrl must be an HTTP(S) base URL without credentials, query or fragment");
        }
        if (method == null || !List.of("POST", "PUT", "PATCH").contains(method)) throw new IllegalArgumentException("method must be POST, PUT or PATCH");
        if (curlExecutable == null || curlExecutable.isBlank()) throw new IllegalArgumentException("curlExecutable is required");
        if (connectTimeoutSeconds == null || requestTimeoutSeconds == null || connectTimeoutSeconds <= 0 || requestTimeoutSeconds <= 0)
            throw new IllegalArgumentException("Timeouts must be positive integers");
        if (outputDirectory == null || outputDirectory.isBlank()) throw new IllegalArgumentException("outputDirectory is required");
        if (payloadTypes == null || payloadTypes.isEmpty()) throw new IllegalArgumentException("payloadTypes must contain at least one entry");
        for (int i = 0; i < payloadTypes.size(); i++) {
            var entry = payloadTypes.get(i);
            if (entry == null || entry.contentType() == null || entry.transferEncoding() == null || entry.payloadSize() == null)
                throw new IllegalArgumentException("payloadTypes[" + i + "] requires contentType, transferEncoding and payloadSize");
            if ((entry.connectTimeoutSeconds() != null && entry.connectTimeoutSeconds() <= 0)
                    || (entry.requestTimeoutSeconds() != null && entry.requestTimeoutSeconds() <= 0))
                throw new IllegalArgumentException("payloadTypes[" + i + "] timeouts must be positive integers");
        }
    }

    public ClientConfig withOutputDirectory(String directory) {
        return new ClientConfig(endpointUrl, method, curlExecutable, connectTimeoutSeconds, requestTimeoutSeconds, directory, payloadTypes);
    }
}
