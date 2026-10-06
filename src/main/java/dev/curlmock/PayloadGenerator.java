package dev.curlmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class PayloadGenerator {
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final ObjectWriter JSON = new ObjectMapper().writer(
            new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));

    public Payload generate(PayloadType type) {
        var fields = new LinkedHashMap<String, String>();
        for (int i = 0; i < 8; i++) fields.put("n" + i + "_" + random(12), random(32));
        String lastName = "n8_" + random(12);
        fields.put(lastName, "");
        String boundary = "curlmock_" + UUID.randomUUID().toString().replace("-", "");
        int overhead = serialize(type.contentType(), fields, boundary).length;
        int remaining = type.payloadSize().bytes() - overhead;
        if (remaining < 0) throw new IllegalArgumentException("Payload size is smaller than format overhead");
        fields.put(lastName, random(remaining));
        byte[] body = serialize(type.contentType(), fields, boundary);
        String mime = type.contentType().mime();
        if (type.contentType() == ContentType.MULTIPART) mime += "; boundary=" + boundary;
        return new Payload(body, mime);
    }

    private static byte[] serialize(ContentType type, LinkedHashMap<String, String> fields, String boundary) {
        if (type == ContentType.JSON) {
            try { return JSON.writeValueAsBytes(fields); }
            catch (java.io.IOException e) { throw new IllegalStateException("Cannot serialize JSON payload", e); }
        }
        var text = new StringBuilder();
        switch (type) {
            case XML -> {
                text.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<payload>\n");
                fields.forEach((name, value) -> text.append("  <").append(name).append('>').append(value)
                        .append("</").append(name).append(">\n"));
                text.append("</payload>\n");
            }
            case FORM -> fields.forEach((name, value) -> {
                if (!text.isEmpty()) text.append('&');
                // The random ASCII alphabet is already safe for form URL encoding.
                text.append(name).append('=').append(value);
            });
            case MULTIPART -> {
                fields.forEach((name, value) -> text.append("--").append(boundary)
                        .append("\r\nContent-Disposition: form-data; name=\"").append(name)
                        .append("\"\r\n\r\n").append(value).append("\r\n"));
                text.append("--").append(boundary).append("--\r\n");
            }
            default -> throw new IllegalArgumentException("Unsupported content type " + type);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String random(int length) {
        char[] result = new char[length];
        for (int i = 0; i < length; i++) result[i] = ALPHABET[ThreadLocalRandom.current().nextInt(ALPHABET.length)];
        return new String(result);
    }
}
