package dev.curlmock;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PayloadType(ContentType contentType, TransferEncoding transferEncoding, PayloadSize payloadSize,
                          Integer connectTimeoutSeconds, Integer requestTimeoutSeconds,
                          java.util.Map<String, String> headers) {
    public PayloadType {
        headers = RequestHeaders.copy(headers);
    }

    public PayloadType(ContentType contentType, TransferEncoding transferEncoding, PayloadSize payloadSize,
                       Integer connectTimeoutSeconds, Integer requestTimeoutSeconds) {
        this(contentType, transferEncoding, payloadSize, connectTimeoutSeconds, requestTimeoutSeconds, java.util.Map.of());
    }
    public PayloadType(ContentType contentType, TransferEncoding transferEncoding, PayloadSize payloadSize) {
        this(contentType, transferEncoding, payloadSize, null, null);
    }

    public int effectiveConnectTimeoutSeconds(ClientConfig config) {
        return connectTimeoutSeconds == null ? config.connectTimeoutSeconds() : connectTimeoutSeconds;
    }

    public int effectiveRequestTimeoutSeconds(ClientConfig config) {
        return requestTimeoutSeconds == null ? config.requestTimeoutSeconds() : requestTimeoutSeconds;
    }
    public String path() {
        return "/CT_" + contentType.token() + "/TE_" + transferEncoding + "/PS_" + payloadSize;
    }
}
