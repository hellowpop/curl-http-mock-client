package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BatchProgressPanelTest {
    @TempDir Path temp;

    @Test void filteredBatchRunsOnlyVisibleCasesIncludingDuplicatesAndShowsResults() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var headers = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(ex.getRequestURI().getPath());
            headers.add(ex.getRequestHeaders().getFirst("X-Test"));
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            var json = new PayloadType(ContentType.JSON, TransferEncoding.GZ, PayloadSize.SM, 2, 4);
            var config = new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(), "PATCH", "curl", 3, 3,
                    temp.toString(), List.of(json, new PayloadType(ContentType.XML, TransferEncoding.NA, PayloadSize.SM), json),
                    List.of("--header", "X-Test: filter-run"));
            BatchProgressPanel[] holder = new BatchProgressPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                var app = new ApplicationPanel(config);
                app.search.setText("json gz");
                app.requests.setSelectedIndex(1);
                holder[0] = app.createBatch(true);
                holder[0].start();
            });
            awaitCompletion(holder[0]);
            Path[] workbook = new Path[1];
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(holder[0].log.getText().contains("선택실행 완료; 총: 2; 성공: 2; 실패: 0"));
                assertFalse(holder[0].log.getText().contains("/CT_xml/"));
                assertTrue(holder[0].viewResults.isVisible());
                workbook[0] = Path.of(holder[0].viewResults.getToolTipText());
            });
            assertEquals(List.of("/CT_json/TE_GZ/PS_SM", "/CT_json/TE_GZ/PS_SM"), received);
            assertEquals(List.of("filter-run", "filter-run"), headers);
            try (var book = new XSSFWorkbook(workbook[0].toFile())) {
                assertEquals(3, book.getSheet("Results").getPhysicalNumberOfRows());
            }
        } finally { server.stop(0); }
    }

    @Test void runsEveryCaseLogsProgressAndOpensWorkbookAfterAllRequestsFinish() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            received.add(ex.getRequestURI().getPath());
            ex.sendResponseHeaders(received.size() == 1 ? 503 : 200, -1);
            ex.close();
        });
        server.start();
        try {
            var config = new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(), "POST", "curl", 3, 3,
                    temp.toString(), List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM),
                    new PayloadType(ContentType.XML, TransferEncoding.GZ, PayloadSize.SM)));
            var opened = new AtomicReference<Path>();
            BatchProgressPanel[] holder = new BatchProgressPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                var app = new ApplicationPanel(config);
                app.search.setText("json");
                assertTrue(app.executeAll.isEnabled());
                var panel = new BatchProgressPanel(config, opened::set);
                holder[0] = panel;
                assertFalse(panel.viewResults.isVisible());
                panel.start();
                panel.start(); // Duplicate starts must not send another batch.
                assertFalse(panel.close.isEnabled());
            });
            awaitCompletion(holder[0]);
            SwingUtilities.invokeAndWait(() -> {
                var panel = holder[0];
                assertTrue(panel.log.getText().contains("/CT_json/TE_NA/PS_SM"));
                assertTrue(panel.log.getText().contains("status=503"));
                assertTrue(panel.log.getText().contains("/CT_xml/TE_GZ/PS_SM"));
                assertTrue(panel.log.getText().contains("2/2"));
                assertTrue(panel.log.getText().contains("성공: 1; 실패: 1"));
                assertTrue(panel.viewResults.isVisible());
                panel.viewResults.doClick();
            });
            assertEquals(List.of("/CT_json/TE_NA/PS_SM", "/CT_xml/TE_GZ/PS_SM"), received);
            assertNotNull(opened.get());
            assertTrue(Files.exists(opened.get()));
            try (var book = new XSSFWorkbook(opened.get().toFile())) {
                assertEquals(3, book.getSheet("Results").getPhysicalNumberOfRows());
            }
        } finally { server.stop(0); }
    }

    @Test void setupFailureShowsErrorAndEnablesCloseWithoutResultButton() throws Exception {
        Path file = temp.resolve("file");
        Files.writeString(file, "not a directory");
        BatchProgressPanel[] holder = new BatchProgressPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new BatchProgressPanel(ClientConfig.sample().withOutputDirectory(file.toString()), path -> fail());
            holder[0].start();
        });
        awaitCompletion(holder[0]);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(holder[0].log.getText().contains("Execution error:"));
            assertFalse(holder[0].viewResults.isVisible());
        });
    }

    private void awaitCompletion(BatchProgressPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean[] complete = {false};
        while (!complete[0] && System.nanoTime() < deadline) {
            Thread.sleep(30);
            SwingUtilities.invokeAndWait(() -> complete[0] = panel.close.isEnabled());
        }
        assertTrue(complete[0], "execution must finish without blocking the EDT");
    }
}
