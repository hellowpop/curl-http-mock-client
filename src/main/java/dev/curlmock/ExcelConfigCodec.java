package dev.curlmock;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class ExcelConfigCodec {
    private static final Set<String> KEYS = Set.of("endpointUrl", "method", "curlExecutable", "connectTimeoutSeconds", "requestTimeoutSeconds", "outputDirectory", "curlArguments");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> TYPE_COLUMNS = List.of("contentType", "transferEncoding", "payloadSize");
    private static final List<String> TIMEOUT_COLUMNS = List.of("connectTimeoutSeconds", "requestTimeoutSeconds");
    private ExcelConfigCodec() {}

    static JsonNode read(Path path) throws IOException {
        try (var stream = Files.newInputStream(path); var book = new XSSFWorkbook(stream)) {
            Sheet settings = requireSheet(book, "Settings", List.of("key", "value"));
            Sheet types = book.getSheet("PayloadTypes");
            if (types == null || types.getRow(0) == null) throw new IllegalArgumentException("Missing sheet/header: PayloadTypes");
            var columns = new java.util.ArrayList<>(TYPE_COLUMNS);
            for (int i = 0; i < 3; i++) {
                if (!TYPE_COLUMNS.get(i).equals(text(types.getRow(0).getCell(i))))
                    throw new IllegalArgumentException("Invalid column in PayloadTypes: expected " + TYPE_COLUMNS.get(i));
            }
            for (int i = 3; i < types.getRow(0).getLastCellNum(); i++) {
                String column = text(types.getRow(0).getCell(i));
                if (column.isEmpty() && i == types.getRow(0).getLastCellNum() - 1) break;
                if (!TIMEOUT_COLUMNS.contains(column) || columns.contains(column))
                    throw new IllegalArgumentException("Invalid or duplicate PayloadTypes column: " + column);
                columns.add(column);
            }
            var root = ConfigFiles.MAPPER.createObjectNode();
            var seen = new HashSet<String>();
            for (int i = 1; i <= settings.getLastRowNum(); i++) {
                Row row = settings.getRow(i);
                if (blank(row)) continue;
                String key = text(row.getCell(0));
                if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown Excel setting at row " + (i + 1) + ": " + key);
                if (!seen.add(key)) throw new IllegalArgumentException("Duplicate Excel setting: " + key);
                String value = text(row.getCell(1));
                if (key.equals("curlArguments")) {
                    if (value.isEmpty()) root.putArray(key);
                    else root.set(key, JSON.readTree(value));
                } else if (key.endsWith("TimeoutSeconds")) {
                    if (!value.matches("[0-9]+")) throw new IllegalArgumentException(key + " must be a positive integer");
                    root.put(key, Integer.parseInt(value));
                } else root.put(key, value);
                rejectExtraCells(row, 2);
            }
            var entries = root.putArray("payloadTypes");
            for (int i = 1; i <= types.getLastRowNum(); i++) {
                Row row = types.getRow(i);
                if (blank(row)) continue;
                var entry = entries.addObject();
                for (int column = 0; column < 3; column++) entry.put(TYPE_COLUMNS.get(column), text(row.getCell(column)));
                for (int column = 3; column < columns.size(); column++) {
                    String value = text(row.getCell(column));
                    if (!value.isEmpty()) {
                        if (!value.matches("[0-9]+")) throw new IllegalArgumentException(columns.get(column) + " must be a positive integer");
                        entry.put(columns.get(column), Integer.parseInt(value));
                    }
                }
                rejectExtraCells(row, columns.size());
            }
            return root;
        }
    }

    static void write(Path path, ClientConfig config) throws IOException {
        try (var book = new XSSFWorkbook()) {
            Sheet settings = book.createSheet("Settings");
            ExcelStyles.header(book, settings, List.of("key", "value"));
            String[][] rows = {
                {"endpointUrl", config.endpointUrl()}, {"method", config.method()}, {"curlExecutable", config.curlExecutable()},
                {"connectTimeoutSeconds", config.connectTimeoutSeconds().toString()},
                {"requestTimeoutSeconds", config.requestTimeoutSeconds().toString()}, {"outputDirectory", config.outputDirectory()},
                {"curlArguments", JSON.writeValueAsString(config.curlArguments())}
            };
            for (int i = 0; i < rows.length; i++) {
                Row row = settings.createRow(i + 1);
                row.createCell(0).setCellValue(rows[i][0]);
                row.createCell(1).setCellValue(rows[i][1]);
            }
            settings.setColumnWidth(0, 30 * 256);
            settings.setColumnWidth(1, 80 * 256);
            Sheet types = book.createSheet("PayloadTypes");
            var columns = new java.util.ArrayList<>(TYPE_COLUMNS);
            columns.addAll(TIMEOUT_COLUMNS);
            ExcelStyles.header(book, types, columns);
            int index = 1;
            for (var type : config.payloadTypes()) {
                Row row = types.createRow(index++);
                row.createCell(0).setCellValue(type.contentType().token());
                row.createCell(1).setCellValue(type.transferEncoding().name());
                row.createCell(2).setCellValue(type.payloadSize().token());
                if (type.connectTimeoutSeconds() != null) row.createCell(3).setCellValue(type.connectTimeoutSeconds());
                if (type.requestTimeoutSeconds() != null) row.createCell(4).setCellValue(type.requestTimeoutSeconds());
            }
            for (int i = 0; i < columns.size(); i++) types.setColumnWidth(i, 26 * 256);
            ExcelStyles.filter(settings);
            ExcelStyles.filter(types);
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
    }

    private static Sheet requireSheet(XSSFWorkbook book, String name, List<String> headers) {
        Sheet sheet = book.getSheet(name);
        if (sheet == null || sheet.getRow(0) == null) throw new IllegalArgumentException("Missing sheet/header: " + name);
        for (int i = 0; i < headers.size(); i++) {
            if (!headers.get(i).equals(text(sheet.getRow(0).getCell(i)))) throw new IllegalArgumentException("Invalid column in " + name + ": expected " + headers.get(i));
        }
        rejectExtraCells(sheet.getRow(0), headers.size());
        return sheet;
    }

    private static String text(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double number = cell.getNumericCellValue();
                if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Excel numeric settings must be nonnegative integers");
                yield Integer.toString((int) number);
            }
            default -> throw new IllegalArgumentException("Excel formulas, errors and boolean cells are not supported: " + cell.getAddress());
        };
    }

    private static boolean blank(Row row) {
        if (row == null) return true;
        for (Cell cell : row) if (!text(cell).isEmpty()) return false;
        return true;
    }

    private static void rejectExtraCells(Row row, int columns) {
        for (Cell cell : row) if (cell.getColumnIndex() >= columns && !text(cell).isEmpty())
            throw new IllegalArgumentException("Unexpected Excel column: " + cell.getAddress());
    }
}
