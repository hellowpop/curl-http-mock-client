package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShutdownIntegrationTest {
    @TempDir Path temp;

    @Test void shutdownSavesCompletedAndCancelledRequestsBeforeJvmExits() throws Exception {
        verifyShutdown(1);
    }

    @Test void shutdownDuringFirstRequestStillSavesAnInterruptedRecord() throws Exception {
        verifyShutdown(0);
    }

    private void verifyShutdown(int successfulRequests) throws Exception {
        var requestCount = new AtomicInteger();
        var active = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Process child = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                int number = requestCount.incrementAndGet();
                if (number > successfulRequests) {
                    active.countDown();
                    try { release.await(20, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                try {
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // Cancellation closes the client socket while the server is blocked.
                } finally { exchange.close(); }
            });
            server.start();
            try {
                var type = new PayloadType(ContentType.JSON, TransferEncoding.CSB, PayloadSize.SM);
                Path results = temp.resolve("results");
                Path config = temp.resolve("config.yml");
                ConfigFiles.write(config, new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(),
                        "POST", "curl", 5, 60, results.toString(), List.of(type, type, type)), false);
                Path trigger = temp.resolve("shutdown.trigger");
                Path childLog = temp.resolve("child.log");
                String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
                child = new ProcessBuilder(java, "-cp", classpath, ShutdownHookProcess.class.getName(),
                        config.toString(), trigger.toString()).redirectErrorStream(true).redirectOutput(childLog.toFile()).start();
                assertTrue(active.await(15, TimeUnit.SECONDS), () -> "No active request: " + read(childLog));
                Files.writeString(trigger, "shutdown");
                assertTrue(child.waitFor(15, TimeUnit.SECONDS), () -> "Shutdown did not finish: " + read(childLog));
                assertEquals(130, child.exitValue(), () -> read(childLog));
                Path workbook;
                try (var paths = Files.list(results)) {
                    var books = paths.filter(p -> p.toString().endsWith(".xlsx")).toList();
                    assertEquals(1, books.size(), () -> "Workbook not saved: " + read(childLog));
                    workbook = books.getFirst();
                }
                try (var book = new XSSFWorkbook(Files.newInputStream(workbook))) {
                    var sheet = book.getSheet("Results");
                    assertEquals(successfulRequests + 2, sheet.getPhysicalNumberOfRows());
                    if (successfulRequests > 0) {
                        assertEquals(200, sheet.getRow(1).getCell(6).getNumericCellValue());
                        assertEquals("", sheet.getRow(1).getCell(9).getStringCellValue());
                    }
                    var cancelled = sheet.getRow(successfulRequests + 1);
                    assertTrue(cancelled.getCell(9).getStringCellValue().toLowerCase().contains("interrupted"));
                    for (int col : new int[] {2, 4, 10}) {
                        var link = cancelled.getCell(col).getHyperlink();
                        assertNotNull(link);
                        assertTrue(Files.exists(workbook.getParent().resolve(link.getAddress())));
                    }
                    Path curlLog = workbook.getParent().resolve(cancelled.getCell(10).getHyperlink().getAddress());
                    assertTrue(Files.readString(curlLog).toLowerCase().contains("interrupted"));
                }
                assertEquals(successfulRequests + 1, requestCount.get(), "No request should start after cancellation");
            } finally {
                if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
                release.countDown();
                server.stop(0);
            }
        }
    }

    private static String read(Path path) {
        try { return Files.exists(path) ? Files.readString(path) : "(log not created)"; }
        catch (IOException e) { return e.toString(); }
    }
    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("windows"); }
}
