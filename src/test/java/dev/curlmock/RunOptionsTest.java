package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class RunOptionsTest {
    @TempDir Path temp;
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();

    int execute(String... args) {
        return new CommandLine(new Main()).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(args);
    }

    @ParameterizedTest @EnumSource(TransferEncoding.class)
    void skipResultSendsBodyWithoutCreatingOutput(TransferEncoding encoding) throws Exception {
        var bodies = new CopyOnWriteArrayList<byte[]>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(ex.getRequestBody().readAllBytes());
            ex.sendResponseHeaders(200, 100000);
            ex.getResponseBody().write(new byte[100000]);
            ex.close();
        });
        server.start();
        try {
            var type = new PayloadType(ContentType.JSON, encoding, PayloadSize.SM);
            Path config = writeConfig(server, List.of(type));
            assertEquals(0, execute("--config", config.toString(), "--skip-result"), err.toString());
            assertEquals(1, bodies.size());
            byte[] body = bodies.getFirst();
            if (encoding == TransferEncoding.GZ) {
                try (var gzip = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(body))) { body = gzip.readAllBytes(); }
            } else if (encoding.contentEncoding() != null) body = AdditionalEncodingsTest.decode(encoding.name(), body);
            assertEquals(2048, body.length);
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            assertTrue(json.isObject());
            assertEquals(9, json.size());
            assertFalse(Files.exists(temp.resolve("results")));
            assertFalse(out.toString().contains("Results:"));
        } finally { server.stop(0); }
    }

    @Test void repeatsWholeSequenceAndRecordsAllResultsIncludingFailures() throws Exception {
        runLoop(false);
    }

    @Test void loopCombinesWithSkipAndPreservesFailureExitCode() throws Exception {
        runLoop(true);
    }

    void runLoop(boolean skip) throws Exception {
        var paths = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            paths.add(ex.getRequestURI().getPath());
            ex.sendResponseHeaders(paths.size() == 1 ? 500 : 200, -1);
            ex.close();
        });
        server.start();
        try {
            var a = new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM);
            var b = new PayloadType(ContentType.JSON, TransferEncoding.CCB, PayloadSize.SM);
            Path config = writeConfig(server, List.of(a, b));
            var args = new ArrayList<>(List.of("--config", config.toString(), "--loop", "3"));
            if (skip) args.add("--skip-result");
            assertEquals(1, execute(args.toArray(String[]::new)), err.toString());
            assertEquals(List.of(a.path(), b.path(), a.path(), b.path(), a.path(), b.path()), paths);
            assertTrue(out.toString().contains("Transactions: 6; succeeded: 5; failed: 1"));
            if (skip) assertFalse(Files.exists(temp.resolve("results")));
            else try (var files = Files.list(temp.resolve("results"))) {
                var workbooks = files.filter(p -> p.toString().endsWith(".xlsx")).toList();
                assertEquals(1, workbooks.size());
                try (var book = new XSSFWorkbook(workbooks.getFirst().toFile())) {
                    assertEquals(6, book.getSheetAt(0).getLastRowNum());
                }
            }
        } finally { server.stop(0); }
    }

    @Test void rejectsInvalidLoopsAndNonCliRunModesBeforeWritingFiles() throws Exception {
        Path validConfig = temp.resolve("valid.yml");
        ConfigFiles.write(validConfig, new ClientConfig("http://127.0.0.1:1", "POST", "curl", 1, 1,
                temp.resolve("results").toString(),
                List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM))), false);
        for (String n : List.of("0", "-1", "abc", "2147483648"))
            assertEquals(2, execute("--config", validConfig.toString(), "--loop", n));
        assertEquals(2, execute("--config", validConfig.toString(), "--loop"));
        assertFalse(Files.exists(temp.resolve("results")));
        for (String[] option : List.of(new String[]{"--skip-result"}, new String[]{"--loop", "1"})) {
            var args = new ArrayList<>(List.of("--sample-yml", temp.resolve("sample.yml").toString()));
            args.addAll(List.of(option));
            assertEquals(2, execute(args.toArray(String[]::new)));
            assertFalse(Files.exists(temp.resolve("sample.yml")));
            args = new ArrayList<>(List.of("--application", "--config", validConfig.toString()));
            args.addAll(List.of(option));
            assertEquals(2, execute(args.toArray(String[]::new)));
        }
    }

    @Test void interruptionStopsRemainingLoopsWithoutSaving() throws Exception {
        var config = new ClientConfig("http://127.0.0.1:1", "POST", "curl", 1, 1,
                temp.resolve("results").toString(),
                List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM)));
        try {
            Thread.currentThread().interrupt();
            var run = new BatchExecutor().run(config, message -> {}, 3, true);
            assertTrue(run.interrupted());
            assertFalse(run.success());
            assertTrue(run.transactions().isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
            assertNull(run.workbook());
            assertFalse(Files.exists(temp.resolve("results")));
        } finally { Thread.interrupted(); }
    }

    @Test void missingCurlReportsExecutionFailureWithoutSaving() throws Exception {
        var config = new ClientConfig("http://127.0.0.1:1", "POST", temp.resolve("missing-curl").toString(), 1, 1,
                temp.resolve("results").toString(),
                List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM)));
        var run = new BatchExecutor().run(config, message -> {}, 2, true);
        assertFalse(run.success());
        assertEquals(2, run.transactions().size());
        assertTrue(run.transactions().stream().allMatch(r -> r.error().contains("Cannot execute curl")));
        assertFalse(Files.exists(temp.resolve("results")));
    }

    Path writeConfig(HttpServer server, List<PayloadType> types) throws Exception {
        Path path = temp.resolve("config.yml");
        ConfigFiles.write(path, new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(),
                "POST", "curl", 3, 5, temp.resolve("results").toString(), types), false);
        return path;
    }
}
