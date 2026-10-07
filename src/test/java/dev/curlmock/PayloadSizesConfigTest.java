package dev.curlmock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PayloadSizesConfigTest {
    @TempDir Path temp;

    private ClientConfig read(String settings) throws Exception {
        Path yaml = temp.resolve("sizes.yml");
        Files.writeString(yaml, "endpointUrl: http://localhost:8080\n" + settings
                + "\npayloadTypes: [{contentType: 'json,xml,form,multipart', transferEncoding: NA, payloadSize: 'SM,CM,LG,20K'}]\n");
        return ConfigFiles.read(yaml);
    }

    @Test void overridesPresetsWithoutChangingTokensAndKeepsUnspecifiedDefaults() throws Exception {
        var config = read("payloadSizes: {SM: 4K, LG: 128K}");
        for (int i = 0; i < config.payloadTypes().size(); i++) {
            var type = config.payloadTypes().get(i);
            assertEquals(List.of(4096, 16384, 131072, 20480).get(i % 4), type.payloadSize().bytes());
            assertEquals(List.of("SM", "CM", "LG", "20K").get(i % 4), type.payloadSize().token());
            assertEquals(type.payloadSize().bytes(), new PayloadGenerator().generate(type).body().length);
        }
        assertEquals(2048, read("").payloadTypes().getFirst().payloadSize().bytes());
        assertEquals(2048, read("payloadSizes: {}").payloadTypes().getFirst().payloadSize().bytes());
    }

    @Test void preservesSettingsThroughConversionCopiesAndRuntimeEditing() throws Exception {
        var config = read("payloadSizes: {SM: 4K, CM: 32K, LG: 128K}");
        Path excel = temp.resolve("sizes.xlsx");
        Path back = temp.resolve("back.yml");
        ConfigFiles.write(excel, config, false);
        ConfigFiles.convert(excel, back, false);
        assertEquals(config, ConfigFiles.read(back));
        assertEquals(4096, ConfigFiles.read(excel).payloadTypes().getFirst().payloadSize().bytes());
        assertEquals(4096, config.withOutputDirectory("other").withAdditionalCurlArguments(List.of()).payloadTypes().getFirst().payloadSize().bytes());
        var summary = new RuntimeSummary();
        summary.show(config, config.payloadTypes().getFirst());
        assertEquals("4096", RuntimeSummaryTest.value(summary, "Payload bytes"));
        RuntimeSummaryTest.set(summary, "Payload sizes", "{\"SM\":\"8K\"}");
        var updated = summary.updated(config, 0);
        assertEquals(8192, updated.payloadTypes().getFirst().payloadSize().bytes());
        assertEquals(16384, updated.payloadTypes().get(1).payloadSize().bytes());
        assertEquals(4096, config.payloadTypes().getFirst().payloadSize().bytes());
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{XX: 4K}", "{SM: null}", "{SM: 1K}", "{SM: 65M}", "{SM: SM}", "{SM: 1.5}", "{SM: true}", "{SM: 4K, SM: 8K}"})
    void rejectsInvalidPresetSettings(String value) {
        assertThrows(Exception.class, () -> read("payloadSizes: " + value));
    }

    @Test void excelBlankOrMissingSettingRestoresLegacyDefaults() throws Exception {
        var config = read("payloadSizes: {SM: 4K}");
        Path excel = temp.resolve("defaults.xlsx");
        for (boolean remove : List.of(false, true)) {
            ConfigFiles.write(excel, config, true);
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(excel))) {
                var sheet = book.getSheet("Settings");
                for (var row : sheet) if ("payloadSizes".equals(row.getCell(0).getStringCellValue())) {
                    if (remove) sheet.removeRow(row);
                    else row.getCell(1).setCellValue("");
                    break;
                }
                try (var out = Files.newOutputStream(excel)) { book.write(out); }
            }
            assertEquals(2048, ConfigFiles.read(excel).payloadTypes().getFirst().payloadSize().bytes());
        }
    }

    @Test void configuredSizesReachSingleSelectedAllAndSkipResultRuns() throws Exception {
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(ex.getRequestURI().getPath() + " " + ex.getRequestBody().readAllBytes().length);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            Path yaml = temp.resolve("runtime.yml");
            Files.writeString(yaml, "endpointUrl: http://127.0.0.1:" + server.getAddress().getPort()
                    + "\npayloadSizes: {SM: 4K}\ncurlArguments: ['--noproxy', '*']\n"
                    + "payloadTypes: [{contentType: 'json,xml', transferEncoding: NA, payloadSize: SM}]\n");
            byte[] original = Files.readAllBytes(yaml);
            var config = ConfigFiles.read(yaml);
            ApplicationPanel[] app = new ApplicationPanel[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                app[0] = new ApplicationPanel(config);
                RuntimeSummaryTest.set(app[0].summary, "Payload sizes", "{\"SM\":\"8K\"}");
                app[0].execute.doClick();
            });
            await(() -> app[0].execute.isEnabled());
            BatchProgressPanel[] batch = new BatchProgressPanel[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> { batch[0] = app[0].createBatch(false); batch[0].start(); });
            await(() -> batch[0].close.isEnabled());
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                app[0].search.setText("xml");
                batch[0] = app[0].createBatch(true);
                batch[0].start();
            });
            await(() -> batch[0].close.isEnabled());
            new BatchExecutor().run(config, message -> {}, 1, true);
            assertEquals(List.of("/CT_json/TE_NA/PS_SM 8192", "/CT_json/TE_NA/PS_SM 8192",
                    "/CT_xml/TE_NA/PS_SM 8192", "/CT_xml/TE_NA/PS_SM 8192",
                    "/CT_json/TE_NA/PS_SM 4096", "/CT_xml/TE_NA/PS_SM 4096"), received);
            assertArrayEquals(original, Files.readAllBytes(yaml));
        } finally { server.stop(0); }
    }

    private void await(java.util.function.BooleanSupplier finished) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        boolean[] done = {false};
        while (!done[0] && System.nanoTime() < deadline) {
            Thread.sleep(30);
            javax.swing.SwingUtilities.invokeAndWait(() -> done[0] = finished.getAsBoolean());
        }
        assertTrue(done[0], "execution must finish");
    }
}
