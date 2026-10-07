package dev.curlmock;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Incremental Unix compress (.Z) encoder with 9..16-bit LZW codes in non-block mode.
 * Not thread safe. Closing also closes the destination; finish() leaves it open.
 */
public final class LzwOutputStream extends OutputStream {
    private final OutputStream destination;
    private final Map<Integer, Integer> dictionary = new HashMap<>();
    private final Codes codes;
    private int next = 256;
    private int prefix = -1;
    private boolean finished;
    private boolean closed;

    public LzwOutputStream(OutputStream destination) throws IOException {
        this.destination = Objects.requireNonNull(destination, "destination");
        codes = new Codes(destination);
        destination.write(new byte[]{0x1f, (byte) 0x9d, 16});
    }

    @Override public void write(int value) throws IOException {
        ensureWritable();
        accept(value & 0xff);
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureWritable();
        for (int i = offset; i < offset + length; i++) accept(bytes[i] & 0xff);
    }

    private void accept(int suffix) throws IOException {
        if (prefix < 0) { prefix = suffix; return; }
        int key = (prefix << 8) | suffix;
        Integer code = dictionary.get(key);
        if (code != null) {
            prefix = code;
        } else {
            codes.write(prefix, next);
            if (next < 65536) dictionary.put(key, next++);
            prefix = suffix;
        }
    }

    /** Completes the stream once without closing the destination. Further writes fail. */
    public void finish() throws IOException {
        ensureOpen();
        if (finished) return;
        finished = true;
        try {
            if (prefix >= 0) codes.write(prefix, next);
            codes.finish();
        } finally { dictionary.clear(); }
    }

    /** Flushes emitted groups; the pending prefix/group (at most 16 bytes) stays buffered. */
    @Override public void flush() throws IOException {
        ensureOpen();
        destination.flush();
    }

    @Override public void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        try { finish(); }
        catch (IOException e) { failure = e; }
        finally {
            closed = true;
            try { destination.close(); }
            catch (IOException e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("LZW stream is closed");
    }
    private void ensureWritable() throws IOException {
        ensureOpen();
        if (finished) throw new IOException("LZW stream is finished");
    }

    /** Little-endian code groups are padded to eight codes when changing width. */
    private static final class Codes {
        private final OutputStream output;
        private final byte[] group = new byte[16];
        private int width = 9;
        private int count;

        Codes(OutputStream output) { this.output = output; }

        void write(int code, int next) throws IOException {
            // Decoder dictionary insertion lags the encoder by one emitted code.
            if (width < 16 && next > (1 << width)) {
                if (count != 0) flush(width);
                width++;
            }
            int start = count * width;
            for (int bit = 0; bit < width; bit++) {
                if ((code & (1 << bit)) != 0) group[(start + bit) / 8] |= (byte) (1 << ((start + bit) % 8));
            }
            if (++count == 8) flush(width);
        }
        void finish() throws IOException {
            if (count != 0) flush((count * width + 7) / 8);
        }
        private void flush(int bytes) throws IOException {
            output.write(group, 0, bytes);
            java.util.Arrays.fill(group, (byte) 0);
            count = 0;
        }
    }
}
