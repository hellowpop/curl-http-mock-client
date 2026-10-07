package dev.curlmock;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import com.aayushatharva.brotli4j.encoder.Encoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Compresses wire entities to an artifact file or an in-memory buffer. */
final class RequestCompression {
    private RequestCompression() {}

    static void write(TransferEncoding encoding, byte[] body, Path path) throws IOException {
        try (var file = Files.newOutputStream(path)) {
            write(encoding, body, file);
        }
    }

    static byte[] encode(TransferEncoding encoding, byte[] body) throws IOException {
        var buffer = new java.io.ByteArrayOutputStream();
        write(encoding, body, buffer);
        return buffer.toByteArray();
    }

    private static void write(TransferEncoding encoding, byte[] body, java.io.OutputStream file) throws IOException {
        if (encoding == TransferEncoding.BR) {
            try { Brotli4jLoader.ensureAvailability(); }
            catch (UnsatisfiedLinkError e) { throw new IOException("Brotli native library is unavailable on this platform", e); }
        }
        try (var compressed = switch (encoding) {
            case GZ -> new GZIPOutputStream(file);
            case DEFLATE -> new DeflaterOutputStream(file);
            case BR -> new BrotliOutputStream(file, new Encoder.Parameters().setQuality(4));
            case COMPRESS -> new LzwOutputStream(file);
            default -> throw new IllegalArgumentException("Not a compressed encoding: " + encoding);
        }) { compressed.write(body); }
    }
}
