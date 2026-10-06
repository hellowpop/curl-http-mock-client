package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class CurlArgumentsTest {
    @TempDir Path temp;
    private Path yaml(String arguments) throws Exception {
        Path path = temp.resolve("config.yml");
        Files.writeString(path, "endpointUrl: http://127.0.0.1:8080\n" + arguments
                + "\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM}]\n");
        return path;
    }

    @Test void preservesArgumentOrderDuplicatesAndWhitespaceThroughBothFormats() throws Exception {
        Path source = yaml("curlArguments: ['--insecure', '--header', 'X-Test: spaced value ', '-H', 'X-Test: second']");
        Path excel = temp.resolve("config.xlsx");
        Path back = temp.resolve("back.yml");
        ConfigFiles.convert(source, excel, false);
        try (var book = new XSSFWorkbook(Files.newInputStream(excel))) {
            var sheet = book.getSheet("Settings");
            var row = java.util.stream.StreamSupport.stream(sheet.spliterator(), false)
                    .filter(r -> "curlArguments".equals(r.getCell(0).getStringCellValue())).findFirst().orElseThrow();
            assertEquals("[\"--insecure\",\"--header\",\"X-Test: spaced value \",\"-H\",\"X-Test: second\"]",
                    row.getCell(1).getStringCellValue());
        }
        ConfigFiles.convert(excel, back, false);
        assertEquals(List.of("--insecure", "--header", "X-Test: spaced value ", "-H", "X-Test: second"),
                ConfigFiles.read(back).curlArguments());
    }

    @Test void passesConfigAndRepeatedCliArgumentsToRealCurlWithoutShellExpansion() throws Exception {
        var captured = new AtomicReference<List<String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            captured.set(List.copyOf(ex.getRequestHeaders().get("X-Test")));
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 2);
            ex.getResponseBody().write("ok".getBytes());
            ex.close();
        });
        server.start();
        try {
            Path source = yaml("curlArguments: ['--noproxy', '*', '--header', 'X-Test: config value']");
            Files.writeString(source, Files.readString(source).replace(":8080", ":" + server.getAddress().getPort()));
            var command = command();
            assertEquals(0, command.execute("--config", source.toString(), "--curl-arg=-H", "--curl-arg=X-Test: cli $(literal); value"));
            assertEquals(List.of("config value", "cli $(literal); value"), captured.get());
            try (var paths = Files.walk(temp.resolve("results"))) {
                Path log = paths.filter(p -> p.toString().endsWith("_curl_log.txt")).findFirst().orElseThrow();
                assertTrue(Files.readString(log).contains("X-Test: cli $(literal); value"));
            }
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {
        "null", "'--insecure'", "[1]", "[true]", "[null]", "['']",
        "['--output', 'other.txt']", "['-oother.txt']", "['--out=other.txt']",
        "['--url', 'http://example.com']", "['http://example.com']", "['--next']",
        "['--config', 'other.conf']", "['--max-time', '10']", "['--data', 'body']",
        "['--header']", "['--header', 'Content-Length: 1']", "['-H', '@headers.txt']",
        "['--header', 'content-type: text/plain']", "['--retry', '3']",
        "['--header', \"X-Test: first\\nsecond\"]", "[\"--insecure\\0\"]",
        "['--proxy', '--output']", "['-kL']", "['--insecure', 'extra-value']"
    }) void rejectsMalformedOrConflictingArgumentsBeforeExecution(String value) throws Exception {
        assertThrows(Exception.class, () -> ConfigFiles.read(yaml("curlArguments: " + value)));
        assertFalse(Files.exists(temp.resolve("results")));
    }

    @Test void rejectsCliArgumentsOutsideRunModeWithoutCreatingOutput() {
        Path output = temp.resolve("sample.yml");
        assertEquals(2, command().execute("--sample-yml", output.toString(), "--curl-arg=--insecure"));
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest @ValueSource(strings = {"['--insecure']", "[] trailing", "null", "true", "[1]", "[null]", "\"--insecure\""})
    void rejectsMalformedExcelArgumentJson(String value) throws Exception {
        Path source = yaml("");
        Path excel = temp.resolve("config.xlsx");
        ConfigFiles.convert(source, excel, false);
        try (var book = new XSSFWorkbook(Files.newInputStream(excel))) {
            var sheet = book.getSheet("Settings");
            var row = java.util.stream.StreamSupport.stream(sheet.spliterator(), false)
                    .filter(r -> "curlArguments".equals(r.getCell(0).getStringCellValue())).findFirst().orElseThrow();
            row.getCell(1).setCellValue(value);
            try (var out = Files.newOutputStream(excel)) { book.write(out); }
        }
        assertThrows(Exception.class, () -> ConfigFiles.read(excel));
    }

    @Test void treatsOmittedAndBlankExcelArgumentsAsEmpty() throws Exception {
        Path source = yaml("");
        assertEquals(List.of(), ConfigFiles.read(source).curlArguments());
        Path excel = temp.resolve("empty.xlsx");
        ConfigFiles.convert(source, excel, false);
        try (var book = new XSSFWorkbook(Files.newInputStream(excel))) {
            var sheet = book.getSheet("Settings");
            var row = java.util.stream.StreamSupport.stream(sheet.spliterator(), false)
                    .filter(r -> "curlArguments".equals(r.getCell(0).getStringCellValue())).findFirst().orElseThrow();
            row.getCell(1).setBlank();
            try (var out = Files.newOutputStream(excel)) { book.write(out); }
        }
        assertEquals(List.of(), ConfigFiles.read(excel).curlArguments());
    }

    @Test void rejectsConflictingCliArgumentBeforeCreatingArtifacts() throws Exception {
        Path source = yaml("");
        assertEquals(2, command().execute("--config", source.toString(), "--curl-arg=--output", "--curl-arg=other.txt"));
        assertFalse(Files.exists(temp.resolve("results")));
    }

    private CommandLine command() {
        var command = new CommandLine(new Main());
        command.setOut(new PrintWriter(new StringWriter()));
        command.setErr(new PrintWriter(new StringWriter()));
        return command;
    }
}
