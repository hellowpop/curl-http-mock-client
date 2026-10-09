package dev.curlmock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class BinaryPayloadTest {
    @TempDir Path temp;

    @ParameterizedTest
    @CsvSource({"BIN,SM,2048", "bin,CM,16384", "application/octet-stream,LG,65536", "BIN,20K,20480", "bin,1M,1048576"})
    void generatesRawBinaryAtConfiguredSize(String contentType, String size, int bytes) {
        var type = new PayloadType(ContentType.parse(contentType), TransferEncoding.NA, PayloadSize.parse(size));
        var generator = new PayloadGenerator();
        var payload = generator.generate(type);
        assertEquals(bytes, payload.body().length);
        assertEquals("application/octet-stream", payload.contentType());
        assertEquals("/CT_bin/TE_NA/PS_" + size, type.path());
        // Binary generation must cover the byte range instead of emitting ASCII fields.
        boolean hasNonAscii = false;
        for (byte value : payload.body()) if (value < 0) { hasNonAscii = true; break; }
        assertTrue(hasNonAscii);
        assertFalse(Arrays.equals(payload.body(), generator.generate(type).body()));
    }

    @Test void preservesBinaryTypesAcrossYamlAndExcel() throws Exception {
        Path yaml = temp.resolve("binary.yml");
        Files.writeString(yaml, "endpointUrl: http://localhost:8080\npayloadTypes:\n"
                + "  - {contentType: BIN, transferEncoding: 'NA,GZ', payloadSize: 'SM,20K'}\n");
        var config = ConfigFiles.read(yaml);
        assertEquals(4, config.payloadTypes().size());
        assertEquals("/CT_bin/TE_GZ/PS_20K", config.payloadTypes().getLast().path());
        Path excel = temp.resolve("binary.xlsx");
        ConfigFiles.convert(yaml, excel, false);
        assertEquals(config, ConfigFiles.read(excel));
        Path back = temp.resolve("binary-back.yml");
        ConfigFiles.convert(excel, back, false);
        assertEquals(config, ConfigFiles.read(back));
    }
}
