package dev.curlmock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.List;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class ResultWorkbook {
    private static final List<String> HEADERS = List.of("uuid", "endpoint url", "request body link", "request header",
            "response body link", "response header", "http status", "curl exit code", "elapsed ms", "error", "curl 실행 입출력 log link");
    private ResultWorkbook() {}

    static void write(Path path, List<TransactionResult> results) throws IOException {
        Path staging = Files.createTempFile(path.toAbsolutePath().getParent(), ".curlmock-results-", ".tmp");
        try {
            try (var book = new XSSFWorkbook()) {
                Sheet sheet = book.createSheet("Results");
                ExcelStyles.header(book, sheet, HEADERS);
                CellStyle wrapped = book.createCellStyle();
                wrapped.setWrapText(true);
                wrapped.setVerticalAlignment(VerticalAlignment.TOP);
                CellStyle linked = book.createCellStyle();
                var font = book.createFont();
                font.setColor(IndexedColors.BLUE.getIndex());
                font.setUnderline(Font.U_SINGLE);
                linked.setFont(font);
                int index = 1;
                for (var result : results) {
                    Row row = sheet.createRow(index++);
                    row.setHeightInPoints(90);
                    row.createCell(0).setCellValue(result.uuid());
                    row.createCell(1).setCellValue(result.endpointUrl());
                    link(book, row.createCell(2), path, result.requestBody(), linked);
                    header(book, row.createCell(3), result.requestHeaders(), CurlRunner.artifact(result.curlLog().getParent(), result.uuid(), "request_headers.txt"), path, wrapped);
                    link(book, row.createCell(4), path, result.responseBody(), linked);
                    header(book, row.createCell(5), result.responseHeaders(), CurlRunner.artifact(result.curlLog().getParent(), result.uuid(), "response_headers.txt"), path, wrapped);
                    row.createCell(6).setCellValue(result.httpStatus());
                    row.createCell(7).setCellValue(result.curlExitCode());
                    row.createCell(8).setCellValue(result.elapsedMs());
                    row.createCell(9).setCellValue(limit(result.error()));
                    link(book, row.createCell(10), path, result.curlLog(), linked);
                }
                for (int i = 0; i < HEADERS.size(); i++) sheet.setColumnWidth(i, (i >= 6 && i <= 8 ? 18 : 48) * 256);
                sheet.setColumnWidth(1, 80 * 256);
                ExcelStyles.filter(sheet);
                try (var out = Files.newOutputStream(staging)) { book.write(out); }
            }
            try { Files.move(staging, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static void header(Workbook book, Cell cell, String text, Path completeFile, Path workbook, CellStyle style) {
        cell.setCellValue(limit(text));
        cell.setCellStyle(style);
        if (text.length() > 32767) {
            var link = book.getCreationHelper().createHyperlink(HyperlinkType.FILE);
            link.setAddress(relative(workbook, completeFile));
            cell.setHyperlink(link);
        }
    }

    private static void link(Workbook book, Cell cell, Path workbook, Path target, CellStyle style) {
        String relative = relative(workbook, target);
        cell.setCellValue(relative);
        var link = book.getCreationHelper().createHyperlink(HyperlinkType.FILE);
        link.setAddress(relative);
        cell.setHyperlink(link);
        cell.setCellStyle(style);
    }

    private static String relative(Path workbook, Path target) {
        return workbook.toAbsolutePath().getParent().relativize(target.toAbsolutePath()).toString().replace('\\', '/');
    }
    private static String limit(String value) {
        return value.length() <= 32767 ? value : value.substring(0, 32650) + "\n[truncated; open linked file for full headers]";
    }
}
