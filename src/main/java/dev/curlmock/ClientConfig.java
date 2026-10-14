package dev.curlmock;

import java.util.List;
import java.util.ArrayList;
import java.net.URI;

public record ClientConfig(String endpointUrl, String method, String curlExecutable,
                           Integer connectTimeoutSeconds, Integer requestTimeoutSeconds,
                           String outputDirectory, List<PayloadType> payloadTypes, List<String> curlArguments,
                           java.util.Map<String, String> headers, java.util.Map<String, String> payloadSizes,
                           String mtlsEndpointUrl, Certificates certificates) {
    public ClientConfig {
        if (curlArguments == null) throw new IllegalArgumentException("curlArguments must be an array");
        curlArguments = java.util.Collections.unmodifiableList(new ArrayList<>(curlArguments));
        headers = RequestHeaders.copy(headers);
        payloadSizes = PayloadSizes.copy(payloadSizes);
        certificates = certificates == null ? Certificates.EMPTY : certificates;
        var sizes = payloadSizes;
        if (payloadTypes != null) payloadTypes = payloadTypes.stream().map(type -> type == null ? null :
                new PayloadType(type.contentType(), type.transferEncoding(), PayloadSizes.resolve(type.payloadSize(), sizes),
                        type.connectTimeoutSeconds(), type.requestTimeoutSeconds(), type.headers(), type.requestType())).toList();
    }

    public ClientConfig(String endpointUrl, String method, String curlExecutable,
                        Integer connectTimeoutSeconds, Integer requestTimeoutSeconds,
                        String outputDirectory, List<PayloadType> payloadTypes, List<String> curlArguments,
                        java.util.Map<String, String> headers, java.util.Map<String, String> payloadSizes) {
        this(endpointUrl, method, curlExecutable, connectTimeoutSeconds, requestTimeoutSeconds,
                outputDirectory, payloadTypes, curlArguments, headers, payloadSizes, null, Certificates.EMPTY);
    }

    public ClientConfig(String endpointUrl, String method, String curlExecutable,
                        Integer connectTimeoutSeconds, Integer requestTimeoutSeconds,
                        String outputDirectory, List<PayloadType> payloadTypes, List<String> curlArguments,
                        java.util.Map<String, String> headers) {
        this(endpointUrl, method, curlExecutable, connectTimeoutSeconds, requestTimeoutSeconds,
                outputDirectory, payloadTypes, curlArguments, headers, java.util.Map.of());
    }

    public ClientConfig(String endpointUrl, String method, String curlExecutable,
                        Integer connectTimeoutSeconds, Integer requestTimeoutSeconds,
                        String outputDirectory, List<PayloadType> payloadTypes, List<String> curlArguments) {
        this(endpointUrl, method, curlExecutable, connectTimeoutSeconds, requestTimeoutSeconds,
                outputDirectory, payloadTypes, curlArguments, java.util.Map.of());
    }

    public ClientConfig(String endpointUrl, String method, String curlExecutable,
                        Integer connectTimeoutSeconds, Integer requestTimeoutSeconds,
                        String outputDirectory, List<PayloadType> payloadTypes) {
        this(endpointUrl, method, curlExecutable, connectTimeoutSeconds, requestTimeoutSeconds,
                outputDirectory, payloadTypes, List.of());
    }

    public static ClientConfig sample() {
        var types = new ArrayList<PayloadType>();
        for (var ct : ContentType.values()) for (var te : TransferEncoding.values()) for (var ps : List.of(PayloadSize.SM, PayloadSize.CM, PayloadSize.LG)) {
            types.add(new PayloadType(ct, te, ps));
        }
        return new ClientConfig("https://localhost:8443", "POST", "curl", 3, 3, "results", List.copyOf(types));
    }
    public void validate() {
        CurlArguments.validate(curlArguments);
        RequestHeaders.validate(headers);
        if (endpointUrl == null || endpointUrl.isBlank()) throw new IllegalArgumentException("endpointUrl is required");
        URI uri = URI.create(endpointUrl);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("endpointUrl must be an HTTP(S) base URL without credentials, query or fragment");
        }
        if (mtlsEndpointUrl != null) requireHttps(mtlsEndpointUrl, "mtlsEndpointUrl");
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
            RequestHeaders.validate(entry.headers());
            if (entry.requestType() == RequestType.TLS) requireHttps(endpointUrl, "TLS endpointUrl");
            if (entry.requestType() == RequestType.MTLS) {
                if (mtlsEndpointUrl == null) throw new IllegalArgumentException("MTLS requires mtlsEndpointUrl");
                certificates.validateMtls();
            }
            if ((entry.connectTimeoutSeconds() != null && entry.connectTimeoutSeconds() <= 0)
                    || (entry.requestTimeoutSeconds() != null && entry.requestTimeoutSeconds() <= 0))
                throw new IllegalArgumentException("payloadTypes[" + i + "] timeouts must be positive integers");
        }
        if (certificates.configured() || payloadTypes.stream().anyMatch(type -> type.requestType() != null))
            CurlArguments.validateTls(curlArguments);
    }

    private static void requireHttps(String value, String field) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException(field + " must be an HTTPS base URL without credentials, query or fragment");
    }

    public String requestUrl(PayloadType type) {
        String base = type.requestType() == RequestType.MTLS ? mtlsEndpointUrl : endpointUrl;
        return base.replaceAll("/+$", "") + type.path();
    }

    void validateExecutionFiles() {
        certificates.validateFiles(payloadTypes.stream().anyMatch(type -> type.requestType() == RequestType.MTLS));
    }

    ClientConfig withCertificates(Certificates value) {
        return new ClientConfig(endpointUrl, method, curlExecutable, connectTimeoutSeconds, requestTimeoutSeconds,
                outputDirectory, payloadTypes, curlArguments, headers, payloadSizes, mtlsEndpointUrl, value);
    }

    public ClientConfig withOutputDirectory(String directory) {
        return new ClientConfig(endpointUrl, method, curlExecutable, connectTimeoutSeconds, requestTimeoutSeconds, directory, payloadTypes, curlArguments, headers, payloadSizes, mtlsEndpointUrl, certificates);
    }

    public ClientConfig withAdditionalCurlArguments(List<String> additional) {
        var combined = new ArrayList<>(curlArguments);
        combined.addAll(additional);
        return new ClientConfig(endpointUrl, method, curlExecutable, connectTimeoutSeconds, requestTimeoutSeconds,
                outputDirectory, payloadTypes, combined, headers, payloadSizes, mtlsEndpointUrl, certificates);
    }
}
