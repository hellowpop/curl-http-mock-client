package dev.curlmock;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Objects;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;

/**
 * Incremental Unix compress (.Z) decoder, supporting block/non-block mode and 9..16 bits.
 * Not thread safe. Closing also closes the source. Mark/reset is not supported.
 */
public final class LzwInputStream extends InputStream {
    private final ZCompressorInputStream decoder;
    private boolean closed;

    public LzwInputStream(InputStream source) throws IOException {
        var input = new PushbackInputStream(Objects.requireNonNull(source, "source"), 3);
        byte[] header = input.readNBytes(3);
        if (header.length != 3) throw new EOFException("Truncated Unix compress (.Z) header");
        int flags = header[2] & 0xff;
        int bits = flags & 0x1f;
        if (header[0] != 0x1f || (header[1] & 0xff) != 0x9d || bits < 9 || bits > 16 || (flags & 0x60) != 0)
            throw new IOException("Invalid Unix compress (.Z) header; expected 9..16-bit LZW");
        input.unread(header);
        decoder = new ZCompressorInputStream(input, 1024);
    }

    @Override public int read() throws IOException { ensureOpen(); return decoder.read(); }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        return decoder.read(bytes, offset, length);
    }

    @Override public long skip(long count) throws IOException { ensureOpen(); return decoder.skip(count); }
    @Override public int available() throws IOException { ensureOpen(); return decoder.available(); }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        decoder.close();
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("LZW stream is closed");
    }
}
