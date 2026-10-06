package dev.curlmock;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.util.Locale;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public final class ConfigFiles {
    static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private ConfigFiles() {}
    public static ClientConfig read(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        JsonNode input;
        if (isExcel(path)) input = ExcelConfigCodec.read(path);
        else try (var stream = Files.newInputStream(path)) { input = MAPPER.readTree(stream); }
        if (input == null || !input.isObject()) throw new IllegalArgumentException("Configuration must be a key/value object");
        validateInputTypes(input);
        ObjectNode defaults = MAPPER.valueToTree(ClientConfig.sample());
        input.fields().forEachRemaining(entry -> defaults.set(entry.getKey(), entry.getValue()));
        // Unlike optional execution settings, payloadTypes and endpointUrl must be supplied.
        if (!input.has("endpointUrl") || !input.has("payloadTypes")) throw new IllegalArgumentException("endpointUrl and payloadTypes are required");
        defaults.set("payloadTypes", PayloadTypesExpansion.expand(input.get("payloadTypes")));
        ClientConfig config = MAPPER.treeToValue(defaults, ClientConfig.class);
        config.validate();
        Path resultDirectory = Path.of(config.outputDirectory());
        if (!resultDirectory.isAbsolute()) resultDirectory = path.getParent().resolve(resultDirectory);
        return config.withOutputDirectory(resultDirectory.toAbsolutePath().normalize().toString());
    }

    public static void write(Path path, ClientConfig config, boolean overwrite) throws IOException {
        config.validate();
        path = path.toAbsolutePath().normalize();
        boolean excel = isExcel(path);
        if (!overwrite && Files.exists(path)) throw new FileAlreadyExistsException(path.toString());
        Files.createDirectories(path.getParent());
        Path staging = Files.createTempFile(path.getParent(), ".curlmock-", ".tmp");
        try {
            if (excel) ExcelConfigCodec.write(staging, config);
            else MAPPER.writeValue(staging.toFile(), config);
            if (overwrite) Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(staging, path);
        } finally { Files.deleteIfExists(staging); }
    }

    public static void convert(Path input, Path output, boolean overwrite) throws IOException {
        write(output, read(input), overwrite);
    }

    static boolean isExcel(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) return true;
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return false;
        throw new IllegalArgumentException("Configuration extension must be .xlsx, .yml or .yaml: " + path);
    }

    private static void validateInputTypes(JsonNode input) {
        for (String key : java.util.List.of("endpointUrl", "method", "curlExecutable", "outputDirectory")) {
            if (input.has(key) && !input.get(key).isTextual()) throw new IllegalArgumentException(key + " must be a string");
        }
        for (String key : java.util.List.of("connectTimeoutSeconds", "requestTimeoutSeconds")) {
            if (input.has(key) && (!input.get(key).isIntegralNumber() || !input.get(key).canConvertToInt()))
                throw new IllegalArgumentException(key + " must be an integer");
        }
        JsonNode entries = input.get("payloadTypes");
        if (entries == null || !entries.isArray()) throw new IllegalArgumentException("payloadTypes must be an array");
        for (JsonNode entry : entries) {
            if (!entry.isObject()) throw new IllegalArgumentException("Each payloadTypes entry must be an object");
            for (String key : java.util.List.of("connectTimeoutSeconds", "requestTimeoutSeconds")) {
                if (entry.has(key) && (!entry.get(key).isIntegralNumber() || !entry.get(key).canConvertToInt()))
                    throw new IllegalArgumentException("payloadTypes." + key + " must be an integer");
            }
            for (String key : java.util.List.of("contentType", "transferEncoding", "payloadSize")) {
                if (!entry.has(key) || !entry.get(key).isTextual()) throw new IllegalArgumentException(key + " must be a string");
            }
        }
    }
}
