package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationPanelTest {
    @TempDir Path temp;

    @Test void executionErrorIsDisplayedAndControlsRecover() throws Exception {
        Path file = temp.resolve("not-a-directory");
        Files.writeString(file, "file");
        var config = ClientConfig.sample().withOutputDirectory(file.toString());
        ApplicationPanel[] holder = new ApplicationPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new ApplicationPanel(config);
            holder[0].execute.doClick();
        });
        awaitCompletion(holder[0]);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(holder[0].result.getText().contains("Execution error:"));
            assertTrue(holder[0].requests.isEnabled());
        });
    }

    @Test void selectionShowsEffectiveSettingsAndRunsOnlySelectedRequest() throws Exception {
        var paths = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            ex.getRequestBody().readAllBytes();
            byte[] body = "{\"message\":\"selected response\",\"items\":[1,2]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            var config = new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(), "PATCH", "curl", 3, 4,
                    temp.toString(), List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM),
                    new PayloadType(ContentType.XML, TransferEncoding.GZ, PayloadSize.SM, 2, 5)), List.of("--header", "X-Test: app"));
            ApplicationPanel[] holder = new ApplicationPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                var panel = new ApplicationPanel(config);
                holder[0] = panel;
                assertEquals(2, panel.requests.getModel().getSize());
                panel.requests.setSelectedIndex(1);
                panel.search.setText("xml");
                assertEquals(1, panel.requests.getModel().getSize());
                assertTrue(RuntimeSummaryTest.value(panel.summary, "URL").contains("/CT_xml/TE_GZ/PS_SM"));
                assertEquals("2", RuntimeSummaryTest.value(panel.summary, "Connect timeout (s)"));
                assertEquals("5", RuntimeSummaryTest.value(panel.summary, "Request timeout (s)"));
                assertTrue(RuntimeSummaryTest.value(panel.summary, "Curl arguments").contains("X-Test: app"));
                panel.execute.doClick();
                assertFalse(panel.execute.isEnabled());
                assertFalse(panel.requests.isEnabled());
                assertFalse(panel.executeAll.isEnabled());
                assertFalse(panel.executeFiltered.isEnabled());
                assertFalse(panel.search.isEnabled());
                assertFalse(panel.clearSearch.isEnabled());
            });
            awaitCompletion(holder[0]);
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(holder[0].result.getText().contains("HTTP status: 200"));
                assertTrue(holder[0].result.getText().contains("selected response"));
                assertTrue(holder[0].result.getText().contains("\n  \"message\""));
                assertTrue(holder[0].requests.isEnabled());
                assertTrue(holder[0].executeAll.isEnabled());
                assertTrue(holder[0].executeFiltered.isEnabled());
                assertTrue(holder[0].search.isEnabled());
                assertTrue(holder[0].clearSearch.isEnabled());
            });
            assertEquals(List.of("/CT_xml/TE_GZ/PS_SM"), paths);
            try (var files = Files.list(temp)) {
                assertEquals(1, files.filter(p -> p.toString().endsWith(".xlsx")).count());
            }
        } finally { server.stop(0); }
    }

    private void awaitCompletion(ApplicationPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean[] finished = {false};
        while (!finished[0] && System.nanoTime() < deadline) {
            Thread.sleep(30);
            SwingUtilities.invokeAndWait(() -> finished[0] = panel.execute.isEnabled());
        }
        assertTrue(finished[0], "UI must recover after execution");
    }
}
