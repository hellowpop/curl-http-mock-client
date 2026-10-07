package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.InflaterInputStream;
import javax.swing.*;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AdditionalEncodingsTest {
    @TempDir Path temp;

    @Test void lowercaseSettingsSurviveExcelConversionAndAreSelectableInGrid() throws Exception {
        Path yaml = temp.resolve("config.yml");
        Files.writeString(yaml, "endpointUrl: http://localhost:8080\npayloadTypes:\n"
                + "  - {contentType: json, transferEncoding: 'deflate,compress,br', payloadSize: SM}\n");
        var config = ConfigFiles.read(yaml);
        assertEquals(List.of("DEFLATE", "COMPRESS", "BR"), config.payloadTypes().stream().map(t -> t.transferEncoding().name()).toList());
        Path excel = temp.resolve("config.xlsx");
        ConfigFiles.convert(yaml, excel, false);
        assertEquals(config, ConfigFiles.read(excel));
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ApplicationPanel(config);
            int row = RuntimeSummaryTest.row(panel.summary, "Transfer-Encoding");
            for (String choice : List.of("DEFLATE", "COMPRESS", "BR")) {
                panel.summary.editCellAt(row, 1);
                var choices = (JComboBox<?>) panel.summary.getEditorComponent();
                choices.setSelectedItem(choice);
                panel.updateSummary.doClick();
                assertEquals(choice, RuntimeSummaryTest.value(panel.summary, "Transfer-Encoding"));
            }
            assertTrue(RuntimeSummaryTest.value(panel.summary, "URL").contains("/TE_BR/"));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"DEFLATE", "COMPRESS", "BR"})
    void sendsCompressedBodyThatIndependentDecoderRestores(String name) throws Exception {
        var bodies = new CopyOnWriteArrayList<byte[]>();
        var headers = new CopyOnWriteArrayList<String>();
        var paths = new CopyOnWriteArrayList<String>();
        var lengths = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(ex.getRequestBody().readAllBytes());
            headers.add(ex.getRequestHeaders().getFirst("Content-Encoding"));
            paths.add(ex.getRequestURI().getPath());
            lengths.add(ex.getRequestHeaders().getFirst("Content-Length"));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            var type = new PayloadType(ContentType.JSON, TransferEncoding.valueOf(name), PayloadSize.parse("1M"));
            var config = new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(), "POST", "curl", 3, 10,
                    temp.resolve("results").toString(), List.of(type));
            var run = new BatchExecutor().run(config);
            assertTrue(run.success());
            assertEquals(List.of(name.toLowerCase(java.util.Locale.ROOT)), headers);
            assertEquals(List.of("/CT_json/TE_" + name + "/PS_1M"), paths);
            byte[] original = Files.readAllBytes(run.transactions().getFirst().requestBody());
            assertArrayEquals(original, decode(name, bodies.getFirst()));
            assertEquals(bodies.getFirst().length, Integer.parseInt(lengths.getFirst()));
            String suffix = switch (name) { case "DEFLATE" -> "deflate"; case "COMPRESS" -> "Z"; default -> "br"; };
            assertArrayEquals(bodies.getFirst(), Files.readAllBytes(run.artifactsDirectory().resolve(
                    run.transactions().getFirst().uuid() + "_request_payload." + suffix)));
        } finally { server.stop(0); }
    }

    @Test void unixCompressHandlesEmptyRepeatedAndFullByteAlphabetInputs() throws Exception {
        byte[] allBytes = new byte[200000];
        new java.util.Random(42).nextBytes(allBytes);
        for (byte[] input : List.of(new byte[0], new byte[]{(byte) 255}, new byte[20000], allBytes)) {
            var output = new java.io.ByteArrayOutputStream();
            UnixCompress.write(input, output);
            assertArrayEquals(input, decode("COMPRESS", output.toByteArray()));
        }
    }

    static byte[] decode(String name, byte[] body) throws java.io.IOException {
        if (name.equals("BR")) Brotli4jLoader.ensureAvailability();
        try (InputStream input = switch (name) {
            case "DEFLATE" -> new InflaterInputStream(new ByteArrayInputStream(body));
            case "COMPRESS" -> new ZCompressorInputStream(new ByteArrayInputStream(body));
            case "BR" -> new BrotliInputStream(new ByteArrayInputStream(body));
            default -> throw new IllegalArgumentException(name);
        }) { return input.readAllBytes(); }
    }
}
