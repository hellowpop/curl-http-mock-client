package dev.curlmock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Expands comma-separated choices in each entry into ordered individual transactions. */
final class PayloadTypesExpansion {
    private PayloadTypesExpansion() {}

    static ArrayNode expand(JsonNode entries) {
        ArrayNode expanded = ConfigFiles.MAPPER.createArrayNode();
        int index = 0;
        for (JsonNode entry : entries) {
            var contentTypes = values(entry, "contentType", index, ContentType::parse);
            var encodings = values(entry, "transferEncoding", index, TransferEncoding::valueOf);
            var sizes = values(entry, "payloadSize", index, PayloadSize::parse);
            for (var ct : contentTypes) for (var te : encodings) for (var ps : sizes) {
                // Keep other fields so Jackson still rejects unknown entry properties.
                ObjectNode combination = entry.deepCopy();
                combination.put("contentType", ct.token());
                combination.put("transferEncoding", te.name());
                combination.put("payloadSize", ps.token());
                expanded.add(combination);
            }
            index++;
        }
        return expanded;
    }

    private static <T> List<T> values(JsonNode entry, String key, int index, Function<String, T> parse) {
        var result = new ArrayList<T>();
        for (String part : entry.get(key).textValue().split(",", -1)) {
            String token = part.strip();
            String field = "payloadTypes[" + index + "]." + key;
            if (token.isEmpty()) throw new IllegalArgumentException(field + " contains an empty value");
            try { result.add(parse.apply(token)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException(field + " has invalid value: " + token, e); }
        }
        return result;
    }
}
