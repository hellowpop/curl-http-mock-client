package dev.curlmock;

public enum TransferEncoding {
    NA(0), GZ(0, "gzip", "gz"), CSB(1024), CCB(8192), CLB(32768),
    DEFLATE(0, "deflate", "deflate"), COMPRESS(0, "compress", "Z"), BR(0, "br", "br");
    private final int blockBytes;
    private final String contentEncoding;
    private final String extension;
    TransferEncoding(int blockBytes) { this(blockBytes, null, null); }
    TransferEncoding(int blockBytes, String contentEncoding, String extension) {
        this.blockBytes = blockBytes;
        this.contentEncoding = contentEncoding;
        this.extension = extension;
    }
    public int blockBytes() { return blockBytes; }
    public boolean chunked() { return blockBytes > 0; }
    public String contentEncoding() { return contentEncoding; }
    public String extension() { return extension; }

    @com.fasterxml.jackson.annotation.JsonCreator
    public static TransferEncoding parse(String value) {
        if (value == null) throw new IllegalArgumentException("transferEncoding is required");
        return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
    }
}
