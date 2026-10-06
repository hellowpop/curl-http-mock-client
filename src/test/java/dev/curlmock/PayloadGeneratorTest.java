package dev.curlmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;

class PayloadGeneratorTest {
    @ParameterizedTest
    @CsvSource({"json,SM,2048", "json,CM,16384", "json,LG,65536", "xml,SM,2048", "xml,CM,16384", "xml,LG,65536",
            "form,SM,2048", "form,CM,16384", "form,LG,65536", "multipart,SM,2048", "multipart,CM,16384", "multipart,LG,65536"})
    void createsValidEntityAtExactSize(String ct, String ps, int bytes) throws Exception {
        var payload = new PayloadGenerator().generate(new PayloadType(ContentType.parse(ct), TransferEncoding.NA, PayloadSize.parse(ps)));
        assertEquals(bytes, payload.body().length);
        var text = new String(payload.body(), StandardCharsets.UTF_8);
        switch (ct) {
            case "json" -> {
                assertTrue(new ObjectMapper().readTree(payload.body()).size() >= 2);
                assertTrue(text.startsWith("{\n  \""), "JSON fields should be indented on separate lines");
                assertTrue(text.endsWith("\n}"));
                assertEquals(11, text.lines().count());
            }
            case "xml" -> {
                assertEquals("payload", DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(new ByteArrayInputStream(payload.body())).getDocumentElement().getTagName());
                assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<payload>\n  <"));
                assertTrue(text.endsWith("\n</payload>\n"));
                assertEquals(12, text.lines().count());
            }
            case "form" -> { assertTrue(text.split("&").length >= 2); assertTrue(text.matches("[a-zA-Z0-9_=&]+")); }
            case "multipart" -> {
                String boundary = payload.contentType().split("boundary=")[1];
                assertTrue(text.startsWith("--" + boundary + "\r\n"));
                assertTrue(text.endsWith("\r\n--" + boundary + "--\r\n"));
                assertTrue(text.contains("Content-Disposition: form-data; name=\""));
            }
            default -> fail(ct);
        }
    }

    @Test void randomizesNamesAndValues() {
        var type = new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM);
        assertFalse(Arrays.equals(new PayloadGenerator().generate(type).body(), new PayloadGenerator().generate(type).body()));
    }

    @ParameterizedTest
    @CsvSource({"json,20K,20480", "json,1M,1048576", "xml,20K,20480", "xml,1M,1048576",
            "form,20K,20480", "form,1M,1048576", "multipart,20K,20480", "multipart,1M,1048576"})
    void generatesExactDirectSizesAcrossAllContentTypes(String ct, String size, int bytes) throws Exception {
        var payload = new PayloadGenerator().generate(new PayloadType(ContentType.parse(ct), TransferEncoding.NA, PayloadSize.parse(size)));
        assertEquals(bytes, payload.body().length);
        if (ct.equals("json")) assertEquals(9, new ObjectMapper().readTree(payload.body()).size());
        if (ct.equals("xml")) assertEquals("payload", DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(payload.body())).getDocumentElement().getTagName());
    }
}
