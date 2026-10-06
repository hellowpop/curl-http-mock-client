package dev.curlmock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResultWorkbookTest {
    @TempDir Path temp;

    @Test void writesRequestedColumnOrderWithMatchingValuesAndLinks() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("run"));
        Path request = Files.writeString(directory.resolve("request.txt"), "request payload");
        Path response = Files.writeString(directory.resolve("response.txt"), "response payload");
        Path log = Files.writeString(directory.resolve("curl.txt"), "curl log");
        var result = new TransactionResult("transaction-1", "http://localhost:8080/endpoint", log,
                "POST /endpoint HTTP/1.1", request, "HTTP/1.1 503 Service Unavailable", response, 503, 28, 1234, "test failure");
        Path workbook = temp.resolve("results.xlsx");
        ResultWorkbook.write(workbook, List.of(result));
        try (var book = new XSSFWorkbook(Files.newInputStream(workbook))) {
            var sheet = book.getSheet("Results");
            var header = sheet.getRow(0);
            assertEquals(List.of("uuid", "endpoint url", "request body link", "request header", "response body link",
                    "response header", "http status", "curl exit code", "elapsed ms", "error", "curl 실행 입출력 log link"),
                    IntStream.range(0, 11).mapToObj(i -> header.getCell(i).getStringCellValue()).toList());
            var row = sheet.getRow(1);
            assertEquals("transaction-1", row.getCell(0).getStringCellValue());
            assertEquals("http://localhost:8080/endpoint", row.getCell(1).getStringCellValue());
            assertEquals("run/request.txt", row.getCell(2).getHyperlink().getAddress());
            assertEquals("POST /endpoint HTTP/1.1", row.getCell(3).getStringCellValue());
            assertEquals("run/response.txt", row.getCell(4).getHyperlink().getAddress());
            assertEquals("HTTP/1.1 503 Service Unavailable", row.getCell(5).getStringCellValue());
            assertEquals(503, row.getCell(6).getNumericCellValue());
            assertEquals(28, row.getCell(7).getNumericCellValue());
            assertEquals(1234, row.getCell(8).getNumericCellValue());
            assertEquals("test failure", row.getCell(9).getStringCellValue());
            assertEquals("run/curl.txt", row.getCell(10).getHyperlink().getAddress());
        }
    }
}
