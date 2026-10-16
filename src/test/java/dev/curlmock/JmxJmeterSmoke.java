package dev.curlmock;

import dev.curlmock.*;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.apache.commons.compress.compressors.z.ZCompressorInputStream;
import com.aayushatharva.brotli4j.decoder.Decoder;

public class JmxJmeterSmoke {
    private static Path jmeterHome;
    private static String jmeterJava;
    public static void main(String[] args) throws Exception {
        com.aayushatharva.brotli4j.Brotli4jLoader.ensureAvailability();
        if (args.length != 3) throw new IllegalArgumentException("Usage: JmxJmeterSmoke JMETER_HOME JAVA_FOR_JMETER OUTPUT_DIRECTORY");
        jmeterHome = Path.of(args[0]).toAbsolutePath();
        jmeterJava = args[1];
        Path root = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(root);
        var failures = new CopyOnWriteArrayList<String>();
        var paths = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                if (path.startsWith("/slow")) {
                    Thread.sleep(2500); ex.sendResponseHeaders(200, -1); ex.close(); return;
                }
                byte[] wire = ex.getRequestBody().readAllBytes(), plain = wire;
                String encoding = ex.getRequestHeaders().getFirst("Content-Encoding");
                if (encoding != null) plain = switch (encoding) {
                    case "gzip" -> new GZIPInputStream(new java.io.ByteArrayInputStream(wire)).readAllBytes();
                    case "deflate" -> new InflaterInputStream(new java.io.ByteArrayInputStream(wire)).readAllBytes();
                    case "compress" -> new ZCompressorInputStream(new java.io.ByteArrayInputStream(wire)).readAllBytes();
                    case "br" -> Decoder.decompress(wire).getDecompressedData();
                    default -> throw new AssertionError(encoding);
                };
                if (plain.length != 2048) throw new AssertionError("length " + plain.length);
                boolean chunked = path.matches(".*/TE_(CSB|CCB|CLB)/.*");
                if (chunked != "chunked".equals(ex.getRequestHeaders().getFirst("Transfer-Encoding")))
                    throw new AssertionError("chunked " + path + " " + ex.getRequestHeaders());
                if (!List.of("PATCH", "PUT", "POST").contains(ex.getRequestMethod())) throw new AssertionError("method");
                if (!"${literal} & value".equals(ex.getRequestHeaders().getFirst("X-Literal"))) throw new AssertionError("literal");
                String unicode = new String(ex.getRequestHeaders().getFirst("X-Unicode").getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                if (!"한글 čĊ & ${literal}".equals(unicode)) throw new AssertionError("Unicode header: " + unicode);
                String ct = ex.getRequestHeaders().getFirst("Content-Type");
                String text = new String(plain, StandardCharsets.UTF_8);
                if (path.contains("CT_multipart") && (!ct.contains("boundary=") || !text.contains(ct.split("boundary=")[1])))
                    throw new AssertionError("boundary");
                paths.add(ex.getRequestMethod() + " " + path);
                ex.sendResponseHeaders(path.contains("CT_xml/TE_NA") ? 500 : 200, 2);
                ex.getResponseBody().write("OK".getBytes()); ex.close();
            } catch (Throwable failure) { failures.add(failure.toString()); ex.close(); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
            var types = new ArrayList<PayloadType>();
            for (var ct : ContentType.values()) for (var te : TransferEncoding.values()) types.add(new PayloadType(ct, te, PayloadSize.SM));
            run(root, "all", new ClientConfig(endpoint, "PATCH", "missing-curl", 2, 5, "unused", types,
                List.of(), Map.of("X-Literal", "${literal} & value", "X-Unicode", "한글 čĊ & ${literal}")));
            if (paths.size() != 40) throw new AssertionError("requests " + paths.size() + " " + failures);
            String all = Files.readString(root.resolve("all.jtl"));
            if (all.lines().filter(line -> line.contains(",false,")).count() != 1) throw new AssertionError("HTTP failure result " + all);
            for (String method : List.of("POST", "PUT")) {
                run(root, method, new ClientConfig(endpoint, method, "missing-curl", 2, 5, "unused",
                    List.of(new PayloadType(ContentType.JSON, TransferEncoding.CLB, PayloadSize.SM)), List.of(), Map.of("X-Literal", "${literal} & value", "X-Unicode", "한글 čĊ & ${literal}")));
                if (Files.readString(root.resolve(method + ".jtl")).contains(",false,")) throw new AssertionError(method + " failed");
            }
            if (paths.size() != 34 || !failures.isEmpty()) throw new AssertionError(failures.toString());
            run(root, "timeout", new ClientConfig(endpoint.replace("/api", "/slow"), "POST", "missing-curl", 2, 1, "unused",
                List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM))));
            if (!Files.readString(root.resolve("timeout.jtl")).contains("TIMEOUT")) throw new AssertionError("deadline");
            System.out.println("PASS: actual JMeter loaded and executed 40 CT/TE requests, POST/PUT chunked requests, HTTP failure continuation and total timeout.");
        } finally { server.stop(0); executor.shutdownNow(); }
    }
    static void run(Path root, String name, ClientConfig config) throws Exception {
        Path input = root.resolve(name + ".yml"), plan = root.resolve(name + ".jmx");
        ConfigFiles.write(input, config, true);
        int exported = new picocli.CommandLine(new Main()).execute(
            "--config", input.toString(), "--export-jmx", plan.toString(), "--overwrite");
        if (exported != 0) throw new AssertionError("export");
        Path result = root.resolve(name + ".jtl"); Files.deleteIfExists(result);
        var process = new ProcessBuilder(jmeterJava, "-jar", jmeterHome.resolve("bin/ApacheJMeter.jar").toString(),
            "-n", "-t", plan.toString(), "-l", result.toString(), "-j", root.resolve(name + ".log").toString())
            .directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve(name + "-cli.log").toFile()).start();
        if (!process.waitFor(40, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("JMeter hung"); }
        if (process.exitValue() != 0) throw new AssertionError(Files.readString(root.resolve(name + "-cli.log")));
    }
}
