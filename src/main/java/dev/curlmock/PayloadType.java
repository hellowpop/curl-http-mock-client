package dev.curlmock;

public record PayloadType(ContentType contentType, TransferEncoding transferEncoding, PayloadSize payloadSize) {
    public String path() {
        return "/CT_" + contentType.token() + "/TE_" + transferEncoding + "/PS_" + payloadSize;
    }
}
