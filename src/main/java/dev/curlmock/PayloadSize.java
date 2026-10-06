package dev.curlmock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Legacy presets and explicitly configured byte sizes, with stable endpoint tokens. */
public final class PayloadSize {
    public static final int MIN_BYTES = 2048;
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public static final PayloadSize SM = new PayloadSize("SM", 2048);
    public static final PayloadSize CM = new PayloadSize("CM", 16384);
    public static final PayloadSize LG = new PayloadSize("LG", 65536);
    private static final Pattern DIRECT = Pattern.compile("([0-9]+)\\s*(B|K|KB|KIB|M|MB|MIB)?");
    private final String token;
    private final int bytes;
    private PayloadSize(String token, int bytes) { this.token = token; this.bytes = bytes; }
    @JsonValue public String token() { return token; }
    public int bytes() { return bytes; }

    @JsonCreator public static PayloadSize parse(String value) {
        if (value == null) throw new IllegalArgumentException("payloadSize is required");
        String text = value.strip().toUpperCase(Locale.ROOT);
        switch (text) {
            case "SM": return SM;
            case "CM": return CM;
            case "LG": return LG;
            default: break;
        }
        var match = DIRECT.matcher(text);
        if (!match.matches()) throw new IllegalArgumentException("Invalid payloadSize: " + value + "; use SM/CM/LG or an integer size such as 20K or 1M");
        String unit = match.group(2);
        String suffix = unit == null || unit.equals("B") ? "B" : unit.startsWith("K") ? "K" : "M";
        long multiplier = switch (suffix) { case "K" -> 1024; case "M" -> 1024 * 1024; default -> 1; };
        try {
            long count = Long.parseLong(match.group(1));
            long bytes = Math.multiplyExact(count, multiplier);
            if (bytes < MIN_BYTES || bytes > MAX_BYTES) throw new IllegalArgumentException("payloadSize must be between 2K and 64M: " + value);
            return new PayloadSize(count + suffix, (int) bytes);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("payloadSize is too large: " + value, e);
        }
    }

    @Override public String toString() { return token; }
    @Override public boolean equals(Object other) {
        return other instanceof PayloadSize size && bytes == size.bytes && token.equals(size.token);
    }
    @Override public int hashCode() { return Objects.hash(token, bytes); }
}
