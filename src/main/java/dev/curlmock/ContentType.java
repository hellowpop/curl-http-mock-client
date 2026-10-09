package dev.curlmock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum ContentType {
    JSON("json", "application/json"), XML("xml", "application/xml"),
    FORM("form", "application/x-www-form-urlencoded"), MULTIPART("multipart", "multipart/form-data"),
    BIN("bin", "application/octet-stream");

    private final String token;
    private final String mime;
    ContentType(String token, String mime) { this.token = token; this.mime = mime; }
    @JsonValue public String token() { return token; }
    public String mime() { return mime; }
    @JsonCreator public static ContentType parse(String value) {
        if (value == null) throw new IllegalArgumentException("contentType is required");
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "json", "application/json" -> JSON;
            case "xml", "application/xml", "text/xml" -> XML;
            case "form", "form-urlencoded", "form_url_encode", "application/x-www-form-urlencoded" -> FORM;
            case "multipart", "multipart_form", "multipart/form-data" -> MULTIPART;
            case "bin", "application/octet-stream" -> BIN;
            default -> throw new IllegalArgumentException("Unknown contentType: " + value);
        };
    }
}
