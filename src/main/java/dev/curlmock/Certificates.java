package dev.curlmock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** File-based curl TLS credentials. File existence is checked only before execution. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record Certificates(String caCertificate, String clientCertificate, String clientKey,
                           String certificateType, String keyPassword) {
    public static final Certificates EMPTY = new Certificates(null, null, null, "PEM", null);
    public Certificates {
        certificateType = certificateType == null ? "PEM" : certificateType.toUpperCase(Locale.ROOT);
        for (String value : new String[]{caCertificate, clientCertificate, clientKey, keyPassword}) {
            if (value != null && (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0))
                throw new IllegalArgumentException("Certificate values must not contain NUL or line breaks");
        }
        for (String value : new String[]{caCertificate, clientCertificate, clientKey}) {
            if (value != null && value.isBlank()) throw new IllegalArgumentException("Certificate paths must not be blank");
        }
        if (!List.of("PEM", "P12").contains(certificateType))
            throw new IllegalArgumentException("certificateType must be PEM or P12");
        if (certificateType.equals("P12") && clientKey != null)
            throw new IllegalArgumentException("P12 includes the private key; omit clientKey");
    }

    void validateMtls() {
        if (clientCertificate == null) throw new IllegalArgumentException("MTLS requires certificates.clientCertificate");
        if (certificateType.equals("PEM") && clientKey == null)
            throw new IllegalArgumentException("PEM MTLS requires certificates.clientKey");
    }

    Certificates resolved(Path base) {
        return new Certificates(resolve(base, caCertificate), resolve(base, clientCertificate), resolve(base, clientKey), certificateType, keyPassword);
    }

    private static String resolve(Path base, String value) {
        return value == null ? null : base.resolve(Path.of(value)).toAbsolutePath().normalize().toString();
    }

    void validateFiles(boolean mtls) {
        readable(caCertificate);
        if (mtls) { readable(clientCertificate); readable(clientKey); }
    }

    private static void readable(String value) {
        if (value != null && (!Files.isRegularFile(Path.of(value)) || !Files.isReadable(Path.of(value))))
            throw new IllegalArgumentException("Certificate file is missing or unreadable: " + value);
    }

    void append(List<String> command, boolean mtls) {
        if (caCertificate != null) command.addAll(List.of("--cacert", caCertificate));
        if (mtls) {
            command.addAll(List.of("--cert-type", certificateType, "--cert", clientCertificate));
            if (clientKey != null) command.addAll(List.of("--key", clientKey));
            if (keyPassword != null) command.addAll(List.of("--pass", keyPassword));
        }
    }

    boolean configured() { return caCertificate != null || clientCertificate != null || clientKey != null || keyPassword != null; }

    @Override public String toString() {
        return "Certificates[caCertificate=" + caCertificate + ", clientCertificate=" + clientCertificate
                + ", clientKey=" + clientKey + ", certificateType=" + certificateType + ", keyPassword=[redacted]]";
    }
}
