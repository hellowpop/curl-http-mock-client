package dev.curlmock;

import java.io.*;
import java.util.List;
import java.util.Random;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LzwStreamsTest {
    private LzwOutputStream output(OutputStream destination) throws IOException { return new LzwOutputStream(destination); }
    private LzwInputStream input(InputStream source) throws IOException { return new LzwInputStream(source); }

    @Test void chunkedWritesAndFlushesDecodeIndependentlyAcrossAllCodeWidths() throws Exception {
        byte[] random = new byte[300000];
        new Random(42).nextBytes(random);
        for (byte[] original : List.of(new byte[0], new byte[]{(byte) 255}, new byte[20000], random)) {
            var encoded = new ByteArrayOutputStream();
            try (var compressed = output(encoded)) {
                int i = 0;
                while (i < original.length) {
                    if (i % 3 == 0) { compressed.write(original[i++]); }
                    else {
                        int n = Math.min(113, original.length - i);
                        compressed.write(original, i, n);
                        i += n;
                    }
                    compressed.flush();
                }
            }
            try (var decoded = new ZCompressorInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
                assertArrayEquals(original, decoded.readAllBytes());
            }
            try (var decoded = input(new ByteArrayInputStream(encoded.toByteArray()))) {
                var copy = new ByteArrayOutputStream();
                byte[] buffer = new byte[117];
                int n;
                while ((n = decoded.read(buffer, 7, 100)) != -1) copy.write(buffer, 7, n);
                assertArrayEquals(original, copy.toByteArray());
                assertEquals(-1, decoded.read());
            }
        }
    }

    @Test void readsKnownExternalNonBlockAndBlockFixturesAndSkipsDecodedBytes() throws Exception {
        // Independently hand-packed 9-bit .Z codes: non-block 65,66,256 => ABAB.
        byte[] plain = {0x1f, (byte) 0x9d, 0x10, 0x41, (byte) 0x84, 0x00, 0x04};
        // Block mode codes 65,66,257 => ABAB (256 is reserved for CLEAR).
        byte[] block = {0x1f, (byte) 0x9d, (byte) 0x90, 0x41, (byte) 0x84, 0x04, 0x04};
        for (byte[] encoded : List.of(plain, block)) {
            try (var decoded = input(new ByteArrayInputStream(encoded))) {
                assertEquals('A', decoded.read());
                assertEquals(2, decoded.skip(2));
                assertEquals('B', decoded.read());
                assertEquals(-1, decoded.read());
                assertEquals(0, decoded.read(new byte[3], 0, 0));
            }
        }
    }

    @Test void finishIsIdempotentKeepsDestinationOpenAndRejectsFurtherWrites() throws Exception {
        var destination = new TrackingOutput();
        var compressed = output(destination);
        compressed.write(new byte[]{1, 2, 3, 4});
        compressed.finish();
        int size = destination.size();
        compressed.finish();
        assertEquals(size, destination.size());
        assertFalse(destination.closed);
        assertThrows(IOException.class, () -> compressed.write(5));
        compressed.close();
        compressed.close();
        assertTrue(destination.closed);
        assertThrows(IOException.class, () -> compressed.write(5));
        try (var decoded = input(new ByteArrayInputStream(destination.toByteArray()))) {
            assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded.readAllBytes());
        }
    }

    @Test void invalidHeadersCodesAndTruncatedHeadersFailAsIoErrors() throws Exception {
        for (byte[] bad : List.of(new byte[0], new byte[]{0x1f}, new byte[]{0x1f, (byte) 0x9d},
                new byte[]{0, 0, 16}, new byte[]{0x1f, (byte) 0x9d, 8},
                new byte[]{0x1f, (byte) 0x9d, 31}, new byte[]{0x1f, (byte) 0x9d, 0x70})) {
            assertThrows(IOException.class, () -> input(new ByteArrayInputStream(bad)));
        }
        // First code 300 cannot refer to an initialized dictionary entry.
        byte[] badCode = {0x1f, (byte) 0x9d, 16, 0x2c, 1};
        try (var decoded = input(new ByteArrayInputStream(badCode))) {
            assertThrows(IOException.class, decoded::readAllBytes);
        }
    }

    @Test void argumentChecksAndCloseOwnershipArePreserved() throws Exception {
        var destination = new TrackingOutput();
        try (var compressed = output(destination)) {
            assertThrows(IndexOutOfBoundsException.class, () -> compressed.write(new byte[3], 2, 2));
            assertThrows(NullPointerException.class, () -> compressed.write(null, 0, 0));
            compressed.write(new byte[]{3, 4});
        }
        var source = new TrackingInput(destination.toByteArray());
        var decoded = input(source);
        assertEquals(0, decoded.read(new byte[2], 0, 0));
        assertEquals(3, decoded.read());
        decoded.close();
        assertTrue(source.closed, "closing decoder must close its source");
        assertThrows(IOException.class, decoded::read);
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() { closed = true; }
    }
    private static final class TrackingInput extends ByteArrayInputStream {
        boolean closed;
        TrackingInput(byte[] input) { super(input); }
        @Override public void close() { closed = true; }
    }

    @Test void externalBlockModeClearResetsDictionaryAndAlignsCodes() throws Exception {
        byte[] encoded = {0x1f, (byte) 0x9d, (byte) 0x90,
                0x41, (byte) 0x84, 0, 4, 0, 0, 0, 0, 0,
                0x43, (byte) 0x88, 4, 4};
        try (var decoded = input(new ByteArrayInputStream(encoded))) {
            assertArrayEquals("ABCDCD".getBytes(java.nio.charset.StandardCharsets.US_ASCII), decoded.readAllBytes());
        }
    }

    @Test void closingOnFinishFailureStillClosesDestinationAndPreservesBothErrors() throws Exception {
        var writeError = new IOException("write failed");
        var closeError = new IOException("close failed");
        var destination = new OutputStream() {
            boolean header = true;
            @Override public void write(int value) throws IOException { if (!header) throw writeError; }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (!header) throw writeError;
                header = false;
            }
            @Override public void close() throws IOException { throw closeError; }
        };
        var compressed = output(destination);
        compressed.write(1);
        var error = assertThrows(IOException.class, compressed::close);
        assertSame(writeError, error);
        assertArrayEquals(new Throwable[]{closeError}, error.getSuppressed());
        compressed.close();
    }
}
