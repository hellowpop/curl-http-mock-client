package dev.curlmock;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-configuration preset sizes; never mutates the shared legacy presets. */
final class PayloadSizes {
    private static final List<String> PRESETS = List.of("SM", "CM", "LG");
    private PayloadSizes() {}

    static Map<String, String> copy(Map<String, String> input) {
        if (input == null) return Map.of();
        var result = new LinkedHashMap<String, String>();
        input.forEach((key, value) -> {
            if (!PRESETS.contains(key)) throw new IllegalArgumentException("payloadSizes supports only SM, CM and LG: " + key);
            var size = PayloadSize.parse(value);
            if (PRESETS.contains(size.token())) throw new IllegalArgumentException("payloadSizes." + key + " requires an explicit size such as 4K or 1M");
            result.put(key, size.token());
        });
        return Collections.unmodifiableMap(result);
    }

    static void validateInput(JsonNode input) {
        if (!input.has("payloadSizes")) return;
        var sizes = input.get("payloadSizes");
        if (!sizes.isObject()) throw new IllegalArgumentException("payloadSizes must be an object of size strings");
        sizes.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) throw new IllegalArgumentException("payloadSizes." + entry.getKey() + " must be a size string");
        });
    }

    static PayloadSize resolve(PayloadSize size, Map<String, String> overrides) {
        if (size == null || !PRESETS.contains(size.token())) return size;
        var effective = PayloadSize.parse(overrides.getOrDefault(size.token(), size.token()));
        return PayloadSize.preset(size.token(), effective.bytes());
    }
}
