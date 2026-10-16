package dev.curlmock;

/** TLS authenticates the server; MTLS additionally authenticates this client. */
public enum RequestType {
    TLS, MTLS;

    @com.fasterxml.jackson.annotation.JsonCreator
    public static RequestType parse(String value) {
        if (value == null) throw new IllegalArgumentException("requestType must be TLS or MTLS");
        return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
    }
}
