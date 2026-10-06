package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CurlIntegrationTest {
    @TempDir Path temp;

    @Test void sendsAllSixtyScenariosThroughRealCurlAndLinksTheirArtifacts() throws Exception {
        var captured = java.util.Collections.synchronizedList(new ArrayList<Captured>());
        byte[] response = new byte[] {0, 1, (byte) 255, 10, 42};
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = server(executor);
            server.createContext("/base", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String ce = exchange.getRequestHeaders().getFirst("Content-Encoding");
                if ("gzip".equals(ce)) try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) { body = gzip.readAllBytes(); }
                captured.add(new Captured(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Content-Type"), ce,
                        exchange.getRequestHeaders().getFirst("Transfer-Encoding"), exchange.getRequestHeaders().getFirst("Content-Length"), body));
                exchange.getResponseHeaders().add("X-Mock-Result", "ok");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            try {
                var config = config(server, ClientConfig.sample().payloadTypes(), 10, "curl");
                var run = new BatchExecutor().run(config);
                assertEquals(60, run.transactions().size());
                assertEquals(60, captured.size());
                assertTrue(run.success());
                assertTrue(run.workbook().getFileName().toString().matches("[0-9]{8}_[0-9]{6}_[0-9]{3}_[a-f0-9-]{36}\\.xlsx"));
                for (int i = 0; i < 60; i++) {
                    var type = config.payloadTypes().get(i);
                    var got = captured.get(i);
                    assertEquals("/base" + type.path(), got.path());
                    assertEquals("POST", got.method());
                    assertEquals(type.payloadSize().bytes(), got.body().length);
                    assertTrue(got.contentType().startsWith(type.contentType().mime()));
                    if (type.transferEncoding().chunked()) { assertEquals("chunked", got.te()); assertNull(got.length()); }
                    else { assertNull(got.te()); assertNotNull(got.length()); }
                    assertEquals(type.transferEncoding() == TransferEncoding.GZ ? "gzip" : null, got.ce());
                    switch (type.contentType()) {
                        case JSON -> assertEquals(9, new ObjectMapper().readTree(got.body()).size());
                        case XML -> assertEquals("payload", DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(got.body())).getDocumentElement().getTagName());
                        case FORM -> assertEquals(9, new String(got.body(), java.nio.charset.StandardCharsets.UTF_8).split("&").length);
                        case MULTIPART -> assertTrue(new String(got.body(), java.nio.charset.StandardCharsets.UTF_8).endsWith("--\r\n"));
                    }
                    var tx = run.transactions().get(i);
                    java.util.UUID.fromString(tx.uuid());
                    assertEquals(200, tx.httpStatus());
                    assertEquals(0, tx.curlExitCode());
                    assertArrayEquals(got.body(), Files.readAllBytes(tx.requestBody()));
                    assertArrayEquals(response, Files.readAllBytes(tx.responseBody()));
                    assertTrue(tx.requestHeaders().contains("Content-Type:"));
                    assertTrue(tx.responseHeaders().contains("X-mock-result: ok"));
                    assertTrue(Files.readString(tx.curlLog()).contains("curl exit code: 0"));
                    if (type.transferEncoding() == TransferEncoding.GZ) assertTrue(Files.exists(run.artifactsDirectory().resolve(tx.uuid() + "_request_payload.gz")));
                }
                try (var book = new XSSFWorkbook(Files.newInputStream(run.workbook()))) {
                    var sheet = book.getSheet("Results");
                    assertEquals(61, sheet.getPhysicalNumberOfRows());
                    assertEquals("uuid", sheet.getRow(0).getCell(0).getStringCellValue());
                    for (int row = 1; row <= 60; row++) {
                        for (int col : new int[] {2, 4, 10}) {
                            var link = sheet.getRow(row).getCell(col).getHyperlink();
                            assertNotNull(link);
                            assertTrue(Files.exists(run.workbook().getParent().resolve(link.getAddress())));
                        }
                    }
                }
            } finally { server.stop(0); }
        }
    }

    @Test void recordsHttpFailuresAndContinues() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = server(executor);
            server.createContext("/", ex -> { ex.getRequestBody().readAllBytes(); ex.sendResponseHeaders(503, 3); ex.getResponseBody().write("bad".getBytes()); ex.close(); });
            server.start();
            try {
                var type = new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM);
                var run = new BatchExecutor().run(config(server, List.of(type, type), 3, "curl"));
                assertFalse(run.success());
                assertEquals(2, run.transactions().size());
                assertEquals(503, run.transactions().getFirst().httpStatus());
                assertEquals("bad", Files.readString(run.transactions().getFirst().responseBody()));
                assertTrue(run.transactions().getFirst().error().contains("503"));
                assertTrue(Files.exists(run.workbook()));
            } finally { server.stop(0); }
        }
    }

    @Test void sendsCustomSizesWithLengthGzipAndChunkedAcrossAllContentTypes() throws Exception {
        var received = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = server(executor);
            server.createContext("/", ex -> {
                byte[] body = ex.getRequestBody().readAllBytes();
                if ("gzip".equals(ex.getRequestHeaders().getFirst("Content-Encoding"))) {
                    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) { body = gzip.readAllBytes(); }
                }
                received.put(ex.getRequestURI().getPath(), body.length);
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            server.start();
            try {
                Path yaml = temp.resolve("sizes.yml");
                Files.writeString(yaml, "endpointUrl: http://127.0.0.1:" + server.getAddress().getPort()
                        + "\npayloadTypes:\n  - contentType: json,xml,form,multipart\n    transferEncoding: NA,GZ,CSB\n    payloadSize: 20K,1M\n");
                var run = new BatchExecutor().run(ConfigFiles.read(yaml));
                assertTrue(run.success());
                assertEquals(24, run.transactions().size());
                assertEquals(24, received.size());
                for (String ct : List.of("json", "xml", "form", "multipart")) for (String te : List.of("NA", "GZ", "CSB")) {
                    assertEquals(20480, received.get("/CT_" + ct + "/TE_" + te + "/PS_20K"));
                    assertEquals(1048576, received.get("/CT_" + ct + "/TE_" + te + "/PS_1M"));
                }
            } finally { server.stop(0); }
        }
    }

    @Test void runsAllEightCommaSeparatedYamlCombinationsThroughCurl() throws Exception {
        var received = java.util.Collections.synchronizedList(new ArrayList<String>());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = server(executor);
            server.createContext("/", ex -> {
                ex.getRequestBody().readAllBytes();
                received.add(ex.getRequestURI().getPath());
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            server.start();
            try {
                Path yaml = temp.resolve("multi.yml");
                Files.writeString(yaml, "endpointUrl: http://127.0.0.1:" + server.getAddress().getPort()
                        + "\npayloadTypes:\n  - contentType: json, xml\n    transferEncoding: GZ,CSB\n    payloadSize: CM,SM\n");
                var run = new BatchExecutor().run(ConfigFiles.read(yaml));
                assertTrue(run.success());
                assertEquals(List.of("/CT_json/TE_GZ/PS_CM", "/CT_json/TE_GZ/PS_SM", "/CT_json/TE_CSB/PS_CM", "/CT_json/TE_CSB/PS_SM",
                        "/CT_xml/TE_GZ/PS_CM", "/CT_xml/TE_GZ/PS_SM", "/CT_xml/TE_CSB/PS_CM", "/CT_xml/TE_CSB/PS_SM"), received);
                assertEquals(8, run.transactions().size());
                try (var book = new XSSFWorkbook(Files.newInputStream(run.workbook()))) {
                    assertEquals(9, book.getSheet("Results").getPhysicalNumberOfRows());
                }
            } finally { server.stop(0); }
        }
    }

    @Test void preservesNonUtf8HttpHeadersWithoutLosingTransaction() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = server(executor);
            server.createContext("/", ex -> {
                ex.getRequestBody().readAllBytes();
                ex.getResponseHeaders().add("X-Label", "caf\u00e9");
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            server.start();
            try {
                var type = new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM);
                var run = new BatchExecutor().run(config(server, List.of(type, type), 5, "curl"));
                assertTrue(run.success());
                assertEquals(2, run.transactions().size());
                assertTrue(run.transactions().getFirst().responseHeaders().contains("caf\u00e9"));
                assertTrue(Files.exists(run.workbook()));
            } finally { server.stop(0); }
        }
    }

    @Test void recordsTimeoutAndKeepsFiles() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = server(executor);
            server.createContext("/", ex -> {
                ex.getRequestBody().readAllBytes();
                try { Thread.sleep(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                ex.close();
            });
            server.start();
            try {
                var type = new PayloadType(ContentType.XML, TransferEncoding.CSB, PayloadSize.SM);
                var run = new BatchExecutor().run(config(server, List.of(type), 1, "curl"));
                assertFalse(run.success());
                assertEquals(28, run.transactions().getFirst().curlExitCode());
                assertTrue(Files.exists(run.transactions().getFirst().responseBody()));
                assertTrue(Files.readString(run.transactions().getFirst().curlLog()).toLowerCase().contains("timed out"));
            } finally { server.stop(0); }
        }
    }

    @Test void recordsMissingCurlAndConnectionRefusal() throws Exception {
        var type = new PayloadType(ContentType.FORM, TransferEncoding.NA, PayloadSize.SM);
        var missing = new ClientConfig("http://127.0.0.1:1", "POST", "no-such-curl-executable", 1, 1, temp.toString(), List.of(type));
        var run = new BatchExecutor().run(missing);
        assertFalse(run.success());
        assertEquals(-1, run.transactions().getFirst().curlExitCode());
        assertFalse(run.transactions().getFirst().error().isBlank());
        assertTrue(Files.exists(run.transactions().getFirst().curlLog()));
        // Windows reports localhost connection refusal after about two seconds.
        var refused = new ClientConfig(missing.endpointUrl(), "PUT", "curl", 5, 5, temp.toString(), List.of(type));
        run = new BatchExecutor().run(refused);
        assertFalse(run.success());
        assertEquals(7, run.transactions().getFirst().curlExitCode());
    }

    private ClientConfig config(HttpServer server, List<PayloadType> types, int timeout, String curl) {
        return new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/base", "POST", curl, 2, timeout, temp.toString(), types);
    }
    private static HttpServer server(java.util.concurrent.Executor executor) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        return server;
    }
    private record Captured(String path, String method, String contentType, String ce, String te, String length, byte[] body) {}
}
