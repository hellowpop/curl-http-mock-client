package dev.curlmock;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validated custom headers; request entries replace common entries ignoring case. */
final class RequestHeaders {
    private static final Set<String> MANAGED = Set.of(
            "content-type", "content-length", "transfer-encoding", "content-encoding", "expect");
    private RequestHeaders() {}

    static Map<String, String> copy(Map<String, String> headers) {
        var copy = new LinkedHashMap<String, String>();
        if (headers != null) copy.putAll(headers);
        return Collections.unmodifiableMap(copy);
    }

    static void validate(Map<String, String> headers) {
        var names = new java.util.HashSet<String>();
        headers.forEach((name, value) -> {
            if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
                throw new IllegalArgumentException("Invalid header name: " + name);
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!names.add(normalized)) throw new IllegalArgumentException("Duplicate header name: " + name);
            if (MANAGED.contains(normalized)) throw new IllegalArgumentException("Application-managed header cannot be overridden: " + name);
            if (value == null || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Header values must be strings without NUL or line breaks: " + name);
        });
    }

    static void validateInput(JsonNode input) {
        if (!input.has("headers")) return;
        JsonNode headers = input.get("headers");
        if (!headers.isObject()) throw new IllegalArgumentException("headers must be an object of string values");
        headers.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) throw new IllegalArgumentException("headers must contain only string values");
        });
    }

    static Map<String, String> merge(Map<String, String> common, Map<String, String> individual) {
        var merged = new LinkedHashMap<>(common);
        individual.forEach((name, value) -> {
            merged.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            merged.put(name, value);
        });
        return merged;
    }

    static void append(List<String> command, ClientConfig config, PayloadType type) {
        var headers = merge(config.headers(), type.headers());
        var arguments = config.curlArguments();
        for (int i = 0; i < arguments.size(); i++) {
            String option = arguments.get(i);
            if (option.equals("--header") || option.equals("-H")) {
                String value = arguments.get(++i);
                int separator = value.indexOf(':');
                if (separator < 0) separator = value.indexOf(';');
                String name = value.substring(0, separator);
                if (headers.keySet().stream().noneMatch(key -> key.equalsIgnoreCase(name))) {
                    command.add(option);
                    command.add(value);
                }
            } else command.add(option);
        }
        headers.forEach((name, value) -> {
            command.add("--header");
            command.add(value.isEmpty() ? name + ";" : name + ": " + value);
        });
    }
}
