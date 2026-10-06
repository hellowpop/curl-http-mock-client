package dev.curlmock;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PayloadType(ContentType contentType, TransferEncoding transferEncoding, PayloadSize payloadSize,
                          Integer connectTimeoutSeconds, Integer requestTimeoutSeconds) {
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
