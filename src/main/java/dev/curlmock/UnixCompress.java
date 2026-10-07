package dev.curlmock;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/** Unix compress (.Z) LZW stream, 9..16 bit codes, non-block mode (no dictionary resets). */
final class UnixCompress {
    private UnixCompress() {}

    static void write(byte[] input, OutputStream output) throws IOException {
        output.write(new byte[]{0x1f, (byte) 0x9d, 16});
        if (input.length == 0) return;
        Map<Integer, Integer> dictionary = new HashMap<>();
        var bits = new Codes(output);
        int next = 256;
        int prefix = input[0] & 0xff;
        for (int i = 1; i < input.length; i++) {
            int suffix = input[i] & 0xff;
            int key = (prefix << 8) | suffix;
            Integer code = dictionary.get(key);
            if (code != null) {
                prefix = code;
            } else {
                bits.write(prefix, next);
                if (next < 65536) dictionary.put(key, next++);
                prefix = suffix;
            }
        }
        bits.write(prefix, next);
        bits.finish();
    }

    /** .Z packs little-endian codes in groups of eight; width changes pad the current group. */
    private static final class Codes {
        private final OutputStream output;
        private final byte[] group = new byte[16];
        private int width = 9;
        private int count;

        Codes(OutputStream output) { this.output = output; }

        void write(int code, int next) throws IOException {
            // The decoder adds its dictionary entry one emitted code later than the encoder.
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
