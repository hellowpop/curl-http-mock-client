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

    @Test void delaySeparatesUnitsIncludingLoopBoundaries() throws Exception {
        var arrivals = new CopyOnWriteArrayList<Long>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            arrivals.add(System.nanoTime());
            ex.sendResponseHeaders(arrivals.size() == 1 ? 500 : 200, -1);
            ex.close();
        });
        server.start();
        try {
            Path config = writeConfig(server, List.of(
                    new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM),
                    new PayloadType(ContentType.JSON, TransferEncoding.CCB, PayloadSize.SM)));
            assertEquals(1, execute("--config", config.toString(), "--loop", "2", "--skip-result", "--delay", "200"), err.toString());
            assertEquals(4, arrivals.size());
            for (int i = 1; i < arrivals.size(); i++)
                assertTrue(arrivals.get(i) - arrivals.get(i - 1) >= 200_000_000L, "Missing inter-unit delay at " + i);
            assertFalse(Files.exists(temp.resolve("results")));
        } finally { server.stop(0); }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(longs = {0, 60000})
    void delayDoesNotWaitBeforeOrAfterSingleUnit(long delay) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            Path config = writeConfig(server, List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM)));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () ->
                    assertEquals(0, execute("--config", config.toString(), "--delay", Long.toString(delay)), err.toString()));
            try (var files = Files.list(temp.resolve("results"))) {
                assertEquals(1, files.filter(p -> p.toString().endsWith(".xlsx")).count());
            }
        } finally { server.stop(0); }
    }

    @Test void interruptionDuringDelaySavesOnlyCompletedUnit() throws Exception {
        var received = new java.util.concurrent.CountDownLatch(1);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            count.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
            received.countDown();
        });
        server.start();
        Thread worker = null;
        try {
            Path config = writeConfig(server, List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM)));
            var exit = new java.util.concurrent.atomic.AtomicInteger(-1);
            var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            worker = new Thread(() -> {
                exit.set(execute("--config", config.toString(), "--loop", "2", "--delay", "60000"));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            worker.start();
            assertTrue(received.await(5, java.util.concurrent.TimeUnit.SECONDS), err.toString());
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (worker.getState() != Thread.State.TIMED_WAITING && worker.isAlive() && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(Thread.State.TIMED_WAITING, worker.getState());
            worker.interrupt();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(1, exit.get());
            assertTrue(interrupted.get());
            assertEquals(1, count.get());
            try (var files = Files.list(temp.resolve("results"))) {
                var workbook = files.filter(p -> p.toString().endsWith(".xlsx")).findFirst().orElseThrow();
                try (var book = new XSSFWorkbook(workbook.toFile())) {
                    assertEquals(1, book.getSheetAt(0).getLastRowNum());
                }
            }
        } finally {
            if (worker != null) { worker.interrupt(); worker.join(5000); }
            server.stop(0);
        }
    }

    @Test void rejectsInvalidDelayAndNonRunModesBeforeWritingFiles() throws Exception {
        Path validConfig = temp.resolve("valid.yml");
        ConfigFiles.write(validConfig, new ClientConfig("http://127.0.0.1:1", "POST", "curl", 1, 1,
                temp.resolve("results").toString(),
                List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM))), false);
        for (String n : List.of("-1", "abc", "1.5", "9223372036854775808"))
            assertEquals(2, execute("--config", validConfig.toString(), "--delay", n));
        assertEquals(2, execute("--config", validConfig.toString(), "--delay"));
        for (String[] modeArgs : List.of(
                new String[]{"--sample-yml", temp.resolve("sample.yml").toString()},
                new String[]{"--sample-excel", temp.resolve("sample.xlsx").toString()},
                new String[]{"--yml-to-excel", validConfig.toString(), "--output", temp.resolve("converted.xlsx").toString()},
                new String[]{"--excel-to-yml", "missing.xlsx", "--output", temp.resolve("converted.yml").toString()},
                new String[]{"--application", "--config", validConfig.toString()},
                new String[]{"--config", validConfig.toString(), "--export-jmx", temp.resolve("export.jmx").toString()})) {
            var args = new ArrayList<>(List.of(modeArgs));
            args.addAll(List.of("--delay", "0"));
            assertEquals(2, execute(args.toArray(String[]::new)));
        }
        try (var files = Files.list(temp)) { assertEquals(List.of(validConfig), files.toList()); }
    }

    Path writeConfig(HttpServer server, List<PayloadType> types) throws Exception {
        Path path = temp.resolve("config.yml");
        ConfigFiles.write(path, new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(),
                "POST", "curl", 3, 5, temp.resolve("results").toString(), types), false);
        return path;
    }
}
