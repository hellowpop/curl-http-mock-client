package dev.curlmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class JmxExportTest {
    @TempDir Path temp;

    @Test void exportsOrderedRequestsWithoutCallingCurlOrCreatingResults() throws Exception {
        var config = new ClientConfig("https://localhost:8443/api/", "PATCH", "missing-curl", 2, 4,
                temp.resolve("results").toString(), List.of(
                new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM),
                new PayloadType(ContentType.MULTIPART, TransferEncoding.GZ, PayloadSize.SM, 5, 7,
                        Map.of("x-test", "개별 & <value> ${literal}")),
                new PayloadType(ContentType.XML, TransferEncoding.CSB, PayloadSize.SM)),
                List.of("--header", "X-Test: argument", "--user-agent", "export-test"), Map.of("X-Test", "common"));
        Path input = temp.resolve("config.yml"), output = temp.resolve("한글 plan.jmx");
        ConfigFiles.write(input, config, false);
        byte[] original = Files.readAllBytes(input);
        assertEquals(0, execute("--config", input.toString(), "--export-jmx", output.toString()));
        assertFalse(Files.exists(temp.resolve("results")));
        assertArrayEquals(original, Files.readAllBytes(input));
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile());
        assertEquals("jmeterTestPlan", document.getDocumentElement().getTagName());
        var samplers = document.getElementsByTagName("JSR223Sampler");
        assertEquals(3, samplers.getLength());
        for (int i = 0; i < 3; i++) {
            var element = (org.w3c.dom.Element) samplers.item(i);
            var data = new ObjectMapper().readTree(Base64.getDecoder().decode(property(element, "parameters")));
            assertEquals("PATCH", data.get("method").asText());
            assertEquals("export-test", data.get("headers").get("User-Agent").asText());
            byte[] wire = Base64.getDecoder().decode(data.get("body").asText());
            if (i == 0) {
                assertEquals("https://localhost:8443/api/CT_json/TE_NA/PS_SM", data.get("url").asText());
                assertEquals("common", data.get("headers").get("X-Test").asText());
                assertEquals(2000, data.get("connectTimeout").asLong());
                assertEquals(4000, data.get("requestTimeout").asLong());
                assertEquals(2048, wire.length);
                assertTrue(new ObjectMapper().readTree(wire).isObject());
            } else if (i == 1) {
                assertEquals("개별 & <value> ${literal}", data.get("headers").get("x-test").asText());
                assertEquals("gzip", data.get("headers").get("Content-Encoding").asText());
                assertEquals(5000, data.get("connectTimeout").asLong());
                assertEquals(7000, data.get("requestTimeout").asLong());
                assertEquals(2048, new GZIPInputStream(new java.io.ByteArrayInputStream(wire)).readAllBytes().length);
                assertTrue(data.get("headers").get("Content-Type").asText().contains("boundary="));
            } else assertEquals(1024, data.get("chunkBytes").asInt());
        }
    }

    @Test void supportsExcelExpandedRequestsAndProtectsExistingFiles() throws Exception {
        Path input = temp.resolve("input.xlsx"), output = temp.resolve("nested/plan.jmx");
        ConfigFiles.write(input, new ClientConfig("http://localhost:1", "POST", "curl", 1, 1, "unused",
                List.of(new PayloadType(ContentType.FORM, TransferEncoding.NA, PayloadSize.SM))), false);
        assertEquals(0, execute("--config", input.toString(), "--export-jmx", output.toString()));
        byte[] original = Files.readAllBytes(output);
        assertEquals(2, execute("--config", input.toString(), "--export-jmx", output.toString()));
        assertArrayEquals(original, Files.readAllBytes(output));
        assertEquals(0, execute("--config", input.toString(), "--export-jmx", output.toString(), "--overwrite"));
        Path yaml = temp.resolve("expanded.yml");
        Files.writeString(yaml, "endpointUrl: http://localhost:1\npayloadTypes:\n  - contentType: json,xml\n    transferEncoding: NA,GZ\n    payloadSize: SM\n");
        assertEquals(0, execute("--config", yaml.toString(), "--export-jmx", output.toString(), "--overwrite"));
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile());
        assertEquals(4, doc.getElementsByTagName("JSR223Sampler").getLength());
    }

    @Test void rejectsIncompatibleModesExtensionsAndUnsupportedCurlOptionsBeforeWriting() throws Exception {
        Path input = temp.resolve("input.yml"), output = temp.resolve("plan.jmx");
        ConfigFiles.write(input, new ClientConfig("http://localhost:1", "POST", "curl", 1, 1, "unused",
                List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM)), List.of("--proxy", "http://localhost:2")), false);
        for (String option : List.of("--application", "--skip-result"))
            assertEquals(2, execute("--config", input.toString(), "--export-jmx", output.toString(), option));
        assertEquals(2, execute("--config", input.toString(), "--export-jmx", output.toString(), "--loop", "2"));
        assertEquals(2, execute("--sample-yml", temp.resolve("sample.yml").toString(), "--export-jmx", output.toString()));
        assertFalse(Files.exists(temp.resolve("sample.yml")));
        assertEquals(2, execute("--config", input.toString(), "--export-jmx", temp.resolve("bad.xml").toString()));
        var err = new StringWriter();
        var command = new CommandLine(new Main()).setErr(new PrintWriter(err));
        assertEquals(2, command.execute("--config", input.toString(), "--export-jmx", output.toString()));
        assertTrue(err.toString().contains("--proxy"));
        assertFalse(Files.exists(output));
        assertEquals(2, execute("--config", input.toString(), "--export-jmx"));
    }

    @Test void rejectsRedirectAndHeaderSuppressionRatherThanChangingRequestSemantics() throws Exception {
        Path input = temp.resolve("input.yml"), output = temp.resolve("plan.jmx");
        for (var arguments : List.of(List.of("--location"), List.of("--header", "User-Agent:"))) {
            ConfigFiles.write(input, new ClientConfig("http://localhost:1", "POST", "curl", 1, 1, "unused",
                    List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM)), arguments), true);
            assertEquals(2, execute("--config", input.toString(), "--export-jmx", output.toString()));
            assertFalse(Files.exists(output));
        }
    }

    @Test void exportsCliCredentialsLiteralCookiesAndEmptyHeaders() throws Exception {
        Path input = temp.resolve("input.yml"), output = temp.resolve("plan.jmx");
        ConfigFiles.write(input, new ClientConfig("http://localhost:1", "POST", "curl", 1, 1, "unused",
                List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM)),
                List.of("--header", "X-Empty;", "--cookie", "session=literal", "--noproxy", "*")), false);
        assertEquals(0, execute("--config", input.toString(), "--export-jmx", output.toString(),
                "--curl-arg=--user", "--curl-arg=user:password", "--curl-arg=--basic", "--curl-arg=--insecure"));
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile());
        var sampler = (org.w3c.dom.Element) doc.getElementsByTagName("JSR223Sampler").item(0);
        var data = new ObjectMapper().readTree(Base64.getDecoder().decode(property(sampler, "parameters")));
        assertEquals("Basic dXNlcjpwYXNzd29yZA==", data.get("headers").get("Authorization").asText());
        assertEquals("session=literal", data.get("headers").get("Cookie").asText());
        assertEquals("", data.get("headers").get("X-Empty").asText());
        assertTrue(data.get("insecure").asBoolean());
    }

    private static String property(org.w3c.dom.Element element, String name) {
        var properties = element.getElementsByTagName("stringProp");
        for (int i = 0; i < properties.getLength(); i++) {
            var property = (org.w3c.dom.Element) properties.item(i);
            if (property.getAttribute("name").equals(name)) return property.getTextContent();
        }
        throw new AssertionError("Missing property " + name);
    }

    private int execute(String... args) {
        return new CommandLine(new Main()).setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(new StringWriter())).execute(args);
    }
}
