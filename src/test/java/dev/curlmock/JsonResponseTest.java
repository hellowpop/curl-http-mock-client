package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class JsonResponseTest {
    @TempDir Path temp;

    @Test void curlSavesPrettyJsonAndPopupDisplaysTheSavedFormattingEvenForHttpErrors() throws Exception {
        String compact = "{\"message\":\"응답\",\"items\":[1,{\"ok\":true}]}";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] bytes = compact.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(422, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        try {
            var config = new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(), "POST", "curl", 3, 3,
                    temp.toString(), List.of(new PayloadType(ContentType.XML, TransferEncoding.NA, PayloadSize.SM)));
            var tx = new BatchExecutor().run(config).transactions().getFirst();
            assertEquals(422, tx.httpStatus());
            String saved = Files.readString(tx.responseBody());
            assertTrue(saved.contains("\n  \"message\""));
            assertTrue(saved.contains("\n    1"), "array elements must be indented too");
            assertTrue(saved.contains("응답"));
            assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(compact),
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved));
            assertEquals(saved, FileContentPopup.read(tx.responseBody()));
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain text", "<html>error</html>", "{\"broken\":", "{} trailing", "{} []"})
    void nonJsonAndIncompleteOrMultipleDocumentsArePreserved(String body) throws Exception {
        Path file = temp.resolve("response.txt");
        Files.writeString(file, body);
        JsonResponse.format(file);
        assertEquals(body, Files.readString(file));
    }

    @Test void preservesExactNumericValuesAndDuplicateFields() throws Exception {
        Path file = temp.resolve("response.txt");
        Files.writeString(file, "{\"n\":1.23456789012345678901234567890,\"n\":123456789012345678901234567890,\"exp\":1.234E+123}");
        JsonResponse.format(file);
        String formatted = Files.readString(file);
        assertTrue(formatted.contains("1.23456789012345678901234567890"));
        assertTrue(formatted.contains("123456789012345678901234567890"));
        assertTrue(formatted.contains("1.234E+123"));
        assertEquals(2, formatted.split("\"n\"", -1).length - 1);
        assertTrue(formatted.contains("\n"));
    }

    @Test void preservesNonJsonBinaryBytes() throws Exception {
        Path file = temp.resolve("response.txt");
        byte[] original = {0, 1, (byte) 255, 42};
        Files.write(file, original);
        JsonResponse.format(file);
        assertArrayEquals(original, Files.readAllBytes(file));
    }
}
