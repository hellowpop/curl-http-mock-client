package dev.curlmock;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PayloadSizeTest {
    @ParameterizedTest
    @CsvSource({"SM,SM,2048", "CM,CM,16384", "LG,LG,65536", "20K,20K,20480", "1M,1M,1048576",
            "20kb,20K,20480", "20KiB,20K,20480", "1mb,1M,1048576", "1MiB,1M,1048576",
            "20480,20480B,20480", "20480B,20480B,20480", "2K,2K,2048", "64M,64M,67108864"})
    void parsesCodesAndDirectSizesWithStablePathTokens(String input, String token, int bytes) {
        var size = PayloadSize.parse(input);
        assertEquals(bytes, size.bytes());
        assertEquals(token, size.toString());
        assertEquals("/CT_json/TE_NA/PS_" + token, new PayloadType(ContentType.JSON, TransferEncoding.NA, size).path());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "BAD", "0", "0K", "1", "2", "1K", "2047B", "-20K", "1.5M", "1G", "65M", "67108865B", "999999999999999999999M"})
    void rejectsInvalidOrUnallocatableSizesBeforeGeneration(String input) {
        assertThrows(IllegalArgumentException.class, () -> PayloadSize.parse(input));
    }
}
