package dev.curlmock;

import com.sun.net.httpserver.HttpServer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path temp;
    @Test void applicationRequiresConfigAndAcceptsAdditionalCurlArguments() {
        var command = new CommandLine(new Main());
        var parsed = command.parseArgs("--application", "--config", "samples/config.yml", "--curl-arg=--insecure");
        assertTrue(parsed.hasMatchedOption("--application"));
        var err = new StringWriter();
        command = new CommandLine(new Main()).setErr(new PrintWriter(err));
        assertEquals(2, command.execute("--application", "--sample-yml", temp.resolve("app.yml").toString()));
        assertTrue(err.toString().contains("--application requires --config"));
        assertFalse(Files.exists(temp.resolve("app.yml")));
        assertEquals(2, execute("--application", "--config", temp.resolve("missing.yml").toString()));
    }

    @Test void createsSamplesAndConvertsBothDirections() throws Exception {
        Path yaml = temp.resolve("sample.yml");
        Path excel = temp.resolve("sample.xlsx");
        assertEquals(0, execute("--sample-yml", yaml.toString()));
        assertEquals(0, execute("--sample-excel", excel.toString()));
        assertEquals(120, ConfigFiles.read(yaml).payloadTypes().size());
        assertEquals(ConfigFiles.read(yaml), ConfigFiles.read(excel));
        Path converted = temp.resolve("converted.xlsx");
        assertEquals(0, execute("--yml-to-excel", yaml.toString(), "--output", converted.toString()));
        Path back = temp.resolve("back.yml");
        assertEquals(0, execute("--excel-to-yml", converted.toString(), "--output", back.toString()));
        assertEquals(ConfigFiles.read(yaml), ConfigFiles.read(back));
    }
    @Test void rejectsConflictingOptionsMissingOutputAndAccidentalOverwrite() throws Exception {
        Path yaml = temp.resolve("sample.yml");
        Path excel = temp.resolve("sample.xlsx");
        assertEquals(2, execute());
        assertEquals(2, execute("--sample-yml", yaml.toString(), "--sample-excel", excel.toString()));
        assertFalse(Files.exists(yaml));
        assertEquals(0, execute("--sample-yml", yaml.toString()));
        assertEquals(2, execute("--sample-yml", yaml.toString()));
        assertEquals(0, execute("--sample-yml", yaml.toString(), "--overwrite"));
        assertEquals(2, execute("--yml-to-excel", yaml.toString()));
        assertEquals(2, execute("--sample-yml", excel.toString()));
        assertEquals(2, execute("--sample-yml", temp.resolve("other.yml").toString(), "--output", excel.toString()));
        assertEquals(2, execute("--excel-to-yml", yaml.toString(), "--output", excel.toString()));
        assertEquals(0, execute("--help"));
    }
    @Test void runsConfigAndReturnsFailureExitCodeForHttpError() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { ex.getRequestBody().readAllBytes(); ex.sendResponseHeaders(500, -1); ex.close(); });
        server.start();
        try {
            var config = new ClientConfig("http://127.0.0.1:" + server.getAddress().getPort(), "PATCH", "curl", 5, 5,
                    temp.resolve("results").toString(), List.of(new PayloadType(ContentType.JSON, TransferEncoding.CCB, PayloadSize.SM)));
            Path path = temp.resolve("config.yml");
            ConfigFiles.write(path, config, false);
            assertEquals(1, execute("--config", path.toString()));
            try (var paths = Files.list(temp.resolve("results"))) { assertTrue(paths.anyMatch(p -> p.toString().endsWith(".xlsx"))); }
        } finally { server.stop(0); }
    }

    @Test void reportsMalformedExcelAsConfigurationErrorWithoutStackTrace() throws Exception {
        Path malformed = temp.resolve("bad.xlsx");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(malformed))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("hello.txt"));
            zip.write("invalid OOXML".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var err = new StringWriter();
        var command = new CommandLine(new Main());
        command.setErr(new PrintWriter(err));
        assertEquals(2, command.execute("--config", malformed.toString()));
        assertTrue(err.toString().startsWith("Configuration error:"));
        assertFalse(err.toString().contains("\tat "));
    }
    private int execute(String... args) {
        var command = new CommandLine(new Main());
        command.setOut(new PrintWriter(new StringWriter()));
        command.setErr(new PrintWriter(new StringWriter()));
        return command.execute(args);
    }
}
