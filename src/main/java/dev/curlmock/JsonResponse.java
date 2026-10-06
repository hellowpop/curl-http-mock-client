package dev.curlmock;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Formats one complete JSON document without changing numeric values or dropping duplicate keys. */
final class JsonResponse {
    private static final JsonFactory JSON = new JsonFactory();
    private JsonResponse() {}

    static void format(Path response) throws IOException {
        if (Files.size(response) == 0) return;
        Path staging = Files.createTempFile(response.toAbsolutePath().getParent(), ".curlmock-json-", ".tmp");
        try {
            try (var parser = JSON.createParser(response.toFile());
                 var output = Files.newOutputStream(staging);
                 var generator = JSON.createGenerator(output)) {
                var indent = new DefaultIndenter("  ", "\n");
                generator.setPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(indent).withArrayIndenter(indent));
                var token = parser.nextToken();
                if (token == null) return;
                int depth = 0;
                do {
                    if (token == null) return;
                    if (token.isNumeric()) generator.writeNumber(parser.getText());
                    else generator.copyCurrentEvent(parser);
                    if (token.isStructStart()) depth++;
                    else if (token.isStructEnd()) depth--;
                    token = parser.nextToken();
                } while (depth > 0);
                // A trailing second document is not a single valid JSON response.
                if (token != null) return;
                generator.writeRaw('\n');
            } catch (JsonProcessingException | java.io.CharConversionException e) {
                return; // Preserve non-JSON, malformed and incomplete response bytes.
            }
            try { Files.move(staging, response, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, response, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(staging); }
    }
}
