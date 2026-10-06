package dev.curlmock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.JButton;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResultFileLinksTest {
    @TempDir Path temp;

    @Test void clickingDisplayedFileOpensTheExactPathWithSpacesAndUnicode() throws Exception {
        Path file = temp.resolve("응답 file & [test].txt");
        Files.writeString(file, "<html>literal response</html>");
        var opened = new AtomicReference<Path>();
        SwingUtilities.invokeAndWait(() -> {
            var pane = new ResultPane(opened::set);
            pane.setSize(1000, 400);
            pane.setText("HTTP status: 200\n");
            pane.appendFile("응답 본문", file);
            var button = (JButton) pane.files.getComponent(0);
            assertEquals("[응답 본문] 응답 file & [test].txt", button.getText());
            assertTrue(button.getToolTipText().contains(file.toString()));
            button.doClick();
            assertEquals(file, opened.get());
            pane.setText("new request");
            assertEquals("new request", pane.getText());
            assertEquals(0, pane.files.getComponentCount());
            assertFalse(pane.files.isVisible());
        });
    }

    @Test void eachResultFileGetsAButtonAndNewResultsReplaceOldButtons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var opened = new AtomicReference<Path>();
            var pane = new ResultPane(opened::set);
            Path workbook = temp.resolve("results.xlsx");
            Path log = temp.resolve("curl_log.txt");
            Path request = temp.resolve("request_payload.txt");
            Path response = temp.resolve("response_payload.txt");
            var transaction = new TransactionResult("id", "http://localhost:8080", log, "request header",
                    request, "response header", response, 200, 0, 42, "");
            pane.showRun(new BatchExecutor.RunResult(workbook, temp, List.of(transaction), false), "response preview");
            assertTrue(pane.getText().contains("HTTP status: 200"));
            assertTrue(pane.getText().contains("response preview"));
            var paths = List.of(workbook, log, request, response);
            var labels = List.of("결과 Excel", "curl 로그", "요청 본문", "응답 본문");
            assertEquals(4, pane.files.getComponentCount());
            for (int i = 0; i < paths.size(); i++) {
                var button = (JButton) pane.files.getComponent(i);
                assertEquals("[" + labels.get(i) + "] " + paths.get(i).getFileName(), button.getText());
                button.doClick();
                assertEquals(paths.get(i), opened.get());
            }
            pane.showRun(new BatchExecutor.RunResult(workbook, temp, List.of(), true), "");
            assertEquals(1, pane.files.getComponentCount());
            assertEquals("[결과 Excel] results.xlsx", ((JButton) pane.files.getComponent(0)).getText());
        });
    }

    @Test void previewsLiteralTextAndBoundsLargeFiles() throws Exception {
        Path file = temp.resolve("response.txt");
        Files.writeString(file, "<script>내용 & text</script>");
        assertEquals("<script>내용 & text</script>", FileContentPopup.read(file));
        Files.writeString(file, "x".repeat(1_100_000));
        String preview = FileContentPopup.read(file);
        assertTrue(preview.length() < 1_060_000);
        assertTrue(preview.contains("미리보기"));
        assertThrows(java.io.IOException.class, () -> FileContentPopup.read(temp.resolve("missing.txt")));
    }

    @Test void previewsWorkbookCellsAcrossSheets() throws Exception {
        Path file = temp.resolve("results.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet("Results").createRow(0);
            row.createCell(0).setCellValue("HTTP status");
            row.createCell(1).setCellValue(200);
            workbook.createSheet("Other").createRow(0).createCell(0).setCellValue("응답 <ok>");
            try (var output = Files.newOutputStream(file)) { workbook.write(output); }
        }
        String preview = FileContentPopup.read(file);
        assertTrue(preview.contains("Results"));
        assertTrue(preview.contains("HTTP status\t200"));
        assertTrue(preview.contains("Other"));
        assertTrue(preview.contains("응답 <ok>"));
    }
}
