package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class HeadersTest {
    @TempDir Path temp;

    private ClientConfig read(String common, String individual) throws Exception {
        Path yaml = temp.resolve("headers.yml");
        Files.writeString(yaml, "endpointUrl: http://127.0.0.1:8080\n" + common
                + "\npayloadTypes:\n- contentType: 'json,xml'\n  transferEncoding: NA\n  payloadSize: SM\n" + individual);
        return ConfigFiles.read(yaml);
    }

    @Test void preservesHeadersThroughExpansionConversionAndRuntimeEditing() throws Exception {
        var config = read("headers: {X-Common: shared, Authorization: 'Bearer common'}",
                "  headers: {authorization: 'Bearer local', X-Local: 'value with spaces'}\n");
        Path excel = temp.resolve("headers.xlsx");
        Path back = temp.resolve("back.yml");
        ConfigFiles.write(excel, config, false);
        ConfigFiles.convert(excel, back, false);
        assertEquals(config, ConfigFiles.read(back));
        for (var type : config.payloadTypes()) {
            assertEquals("Bearer local", ConfigFiles.MAPPER.valueToTree(type).get("headers").get("authorization").textValue());
        }
        var summary = new RuntimeSummary();
        summary.show(config, config.payloadTypes().getFirst());
        assertEquals(config, summary.updated(config, 0));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sendsCommonHeadersAndOneCaseInsensitiveOverrideWithRealCurl(boolean skipResult) throws Exception {
        var captured = new CopyOnWriteArrayList<List<String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            captured.add(List.of(ex.getRequestHeaders().getFirst("X-Common"),
                    String.join("|", ex.getRequestHeaders().get("Authorization")),
                    ex.getRequestHeaders().getFirst("X-Local"),
                    ex.getRequestHeaders().getFirst("X-Empty") == null ? "absent" : "present"));
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            var config = read("headers: {X-Common: shared, Authorization: 'Bearer common', X-Empty: ''}\n"
                    + "curlArguments: ['--noproxy', '*', '-H', 'Authorization: legacy']",
                    "  headers: {authorization: 'Bearer local', X-Local: request}\n");
            Path yaml = temp.resolve("headers.yml");
            Files.writeString(yaml, Files.readString(yaml).replace(":8080", ":" + server.getAddress().getPort()));
            config = ConfigFiles.read(yaml);
            var run = new BatchExecutor().run(config, message -> {}, 1, skipResult);
            assertTrue(run.transactions().stream().allMatch(t -> t.httpStatus() == 200));
            assertEquals(List.of(List.of("shared", "Bearer local", "request", "present"),
                    List.of("shared", "Bearer local", "request", "present")), captured);
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{X-Test: 1}", "{X-Test: null}",
            "{Bad Header: value}", "{X-Test: a, x-test: b}", "{X-Test: a, X-Test: b}", "{Content-Length: '1'}", "{X-Test: \"a\\nb\"}"})
    void rejectsInvalidHeadersAtBothScopes(String headers) {
        assertThrows(Exception.class, () -> read("headers: " + headers, ""));
        assertThrows(Exception.class, () -> read("", "  headers: " + headers + "\n"));
    }

    @Test void runtimeHeadersReachSingleSelectedAndAllRuns() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(ex.getRequestHeaders().getFirst("X-Common") + " "
                    + String.join("|", ex.getRequestHeaders().get("Authorization")));
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            read("headers: {X-Common: initial, Authorization: common}", "");
            Path yaml = temp.resolve("headers.yml");
            Files.writeString(yaml, Files.readString(yaml).replace(":8080", ":" + server.getAddress().getPort()));
            byte[] original = Files.readAllBytes(yaml);
            var config = ConfigFiles.read(yaml);
            ApplicationPanel[] app = new ApplicationPanel[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                app[0] = new ApplicationPanel(config);
                RuntimeSummaryTest.set(app[0].summary, "Common headers", "{\"X-Common\":\"edited\",\"Authorization\":\"common\"}");
                RuntimeSummaryTest.set(app[0].summary, "Request headers", "{\"authorization\":\"local\"}");
                app[0].execute.doClick();
            });
            await(() -> app[0].execute.isEnabled());
            BatchProgressPanel[] batch = new BatchProgressPanel[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> { batch[0] = app[0].createBatch(false); batch[0].start(); });
            await(() -> batch[0].close.isEnabled());
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                app[0].search.setText("json");
                batch[0] = app[0].createBatch(true);
                batch[0].start();
            });
            await(() -> batch[0].close.isEnabled());
            assertEquals(List.of("edited local", "edited local", "edited common", "edited local"), received);
            assertArrayEquals(original, Files.readAllBytes(yaml));
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "{\"X-Test\":1}", "{\"X-Test\":\"a\",\"x-test\":\"b\"}",
            "{\"X-Test\":\"a\",\"X-Test\":\"b\"}"})
    void rejectsMalformedExcelHeadersAtBothScopes(String value) throws Exception {
        var config = read("", "");
        for (boolean common : List.of(true, false)) {
            Path excel = temp.resolve("invalid.xlsx");
            ConfigFiles.write(excel, config, true);
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(excel))) {
                if (common) {
                    var sheet = book.getSheet("Settings");
                    for (var row : sheet) if ("headers".equals(row.getCell(0).getStringCellValue()))
                        row.getCell(1).setCellValue(value);
                } else book.getSheet("PayloadTypes").getRow(1).getCell(5).setCellValue(value);
                try (var out = Files.newOutputStream(excel)) { book.write(out); }
            }
            assertThrows(Exception.class, () -> ConfigFiles.read(excel));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"Common headers", "Request headers"})
    void rejectsDuplicateRuntimeHeaderKeysWithoutUpdatingConfig(String scope) throws Exception {
        var config = read("", "");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var app = new ApplicationPanel(config);
            RuntimeSummaryTest.set(app.summary, scope, "{\"X-Test\":\"a\",\"X-Test\":\"b\"}");
            app.updateSummary.doClick();
            // Switching away and back discards the draft and shows the stored selected request.
            app.requests.setSelectedIndex(1);
            app.requests.setSelectedIndex(0);
            assertEquals("{}", RuntimeSummaryTest.value(app.summary, scope));
        });
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
