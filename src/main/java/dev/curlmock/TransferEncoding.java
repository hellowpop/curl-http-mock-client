package dev.curlmock;

public enum TransferEncoding {
    NA(0), GZ(0), CSB(1024), CCB(8192), CLB(32768);
    private final int blockBytes;
    TransferEncoding(int blockBytes) { this.blockBytes = blockBytes; }
    public int blockBytes() { return blockBytes; }
    public boolean chunked() { return blockBytes > 0; }
}
