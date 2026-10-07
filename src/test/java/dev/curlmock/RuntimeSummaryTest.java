package dev.curlmock;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSummaryTest {
    @TempDir Path temp;

    @Test void editedValuesReachSingleAndBatchRunsWithoutChangingSourceFile() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                    + ex.getRequestHeaders().getFirst("X-Test"));
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            Path source = temp.resolve("config.yml");
            var type = new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM);
            var original = new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(), "POST", "curl",
                    3, 4, temp.resolve("results").toString(), List.of(type, type));
            ConfigFiles.write(source, original, false);
            byte[] before = Files.readAllBytes(source);
            ApplicationPanel[] app = new ApplicationPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                app[0] = new ApplicationPanel(original);
                JTable grid = find(app[0], JTable.class, null);
                assertNotNull(grid, "execution summary must be an editable grid");
                set(grid, "Method", "PUT");
                set(grid, "Content-Type", "XML");
                set(grid, "Payload", "4K");
                set(grid, "Curl arguments", "[\"--header\",\"X-Test: runtime\"]");
                find(app[0], JButton.class, "업데이트").doClick();
                app[0].requests.setSelectedIndex(1);
                assertEquals("JSON", value(grid, "Content-Type"));
                assertEquals("PUT", value(grid, "Method"));
                app[0].requests.setSelectedIndex(0);
                assertEquals("XML", value(grid, "Content-Type"));
                // Execution must commit the active editor even without pressing Update.
                int row = row(grid, "Request timeout (s)");
                grid.editCellAt(row, 1);
                ((JTextField) grid.getEditorComponent()).setText("7");
                app[0].execute.doClick();
                assertFalse(grid.isEnabled());
            });
            await(() -> app[0].execute.isEnabled());
            BatchProgressPanel[] batch = new BatchProgressPanel[1];
            SwingUtilities.invokeAndWait(() -> { batch[0] = app[0].createBatch(false); batch[0].start(); });
            await(() -> batch[0].close.isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                app[0].search.setText("xml");
                batch[0] = app[0].createBatch(true);
                batch[0].start();
            });
            await(() -> batch[0].close.isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                set(app[0].summary, "Content-Type", "FORM");
                app[0].execute.doClick();
                assertEquals(0, app[0].requests.getModel().getSize());
                assertFalse(app[0].requests.isEnabled());
            });
            await(() -> app[0].requests.isEnabled());
            assertEquals(List.of("PUT /CT_xml/TE_NA/PS_4K runtime", "PUT /CT_xml/TE_NA/PS_4K runtime",
                    "PUT /CT_json/TE_NA/PS_SM runtime", "PUT /CT_xml/TE_NA/PS_4K runtime",
                    "PUT /CT_form/TE_NA/PS_4K runtime"), received);
            assertArrayEquals(before, Files.readAllBytes(source));
            assertEquals(type, original.payloadTypes().getFirst());
            var reloaded = ConfigFiles.read(source);
            SwingUtilities.invokeAndWait(() -> assertEquals("POST", value(find(new ApplicationPanel(reloaded), JTable.class, null), "Method")));
        } finally { server.stop(0); }
    }

    @Test void invalidUpdateIsAtomicAndDoesNotStartExecution() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var app = new ApplicationPanel(ClientConfig.sample().withOutputDirectory(temp.toString()));
            JTable grid = find(app, JTable.class, null);
            assertNotNull(grid, "execution summary must be an editable grid");
            set(grid, "Method", "PUT");
            set(grid, "Connect timeout (s)", "0");
            app.execute.doClick();
            assertTrue(app.requests.isEnabled());
            assertFalse(app.result.getText().contains("실행 중"));
            app.requests.setSelectedIndex(1);
            assertEquals("POST", value(grid, "Method"));
            set(grid, "Curl arguments", "[\"--output\",\"other.txt\"]");
            find(app, JButton.class, "업데이트").doClick();
            app.requests.setSelectedIndex(0);
            assertEquals("[]", value(grid, "Curl arguments"));
        });
        try (var files = Files.list(temp)) { assertEquals(0, files.count()); }
    }

    static int row(JTable grid, String label) {
        for (int i = 0; i < grid.getRowCount(); i++) if (label.equals(grid.getValueAt(i, 0))) return i;
        throw new AssertionError("Missing row: " + label);
    }
    static String value(JTable grid, String label) { return grid.getValueAt(row(grid, label), 1).toString(); }
    static void set(JTable grid, String label, String value) { grid.setValueAt(value, row(grid, label), 1); }
    static <T extends Component> T find(Container container, Class<T> type, String text) {
        for (Component c : container.getComponents()) {
            if (type.isInstance(c) && (text == null || c instanceof JButton b && text.equals(b.getText()))) return type.cast(c);
            if (c instanceof Container child) { T found = find(child, type, text); if (found != null) return found; }
        }
        return null;
    }
    private void await(java.util.function.BooleanSupplier finished) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean[] done = {false};
        while (!done[0] && System.nanoTime() < deadline) {
            Thread.sleep(30);
            SwingUtilities.invokeAndWait(() -> done[0] = finished.getAsBoolean());
        }
        assertTrue(done[0], "execution must finish");
    }
}
