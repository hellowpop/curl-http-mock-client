package dev.curlmock;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Uses JDK keytool fixtures, a real HTTPS server and the system curl (including Windows Schannel). */
class MtlsIntegrationTest {
    private static final String PASSWORD = "tls-test-secret";
    @TempDir static Path fixture;
    @TempDir Path temp;

    @BeforeAll static void certificates() throws Exception {
        generate("ca", "CN=Curlmock Test CA", "bc=ca:true");
        keytool("-exportcert", "-alias", "ca", "-keystore", fixture.resolve("ca.p12").toString(),
                "-storepass", PASSWORD, "-rfc", "-file", fixture.resolve("ca.pem").toString());
        signed("server", "CN=localhost", "serverAuth");
        signed("client", "CN=curlmock-client", "clientAuth");
        signed("rsa-client", "CN=curlmock-rsa-client", "clientAuth");
        generate("rogue", "CN=untrusted-client", "eku=clientAuth");
        try (var in = Files.newInputStream(fixture.resolve("client.p12"))) {
            var store = KeyStore.getInstance("PKCS12");
            store.load(in, PASSWORD.toCharArray());
            var key = store.getKey("client", PASSWORD.toCharArray());
            Files.writeString(fixture.resolve("client.key"), pem("PRIVATE KEY", key.getEncoded()));
            var chain = store.getCertificateChain("client");
            var text = new StringBuilder();
            for (var cert : chain) text.append(pem("CERTIFICATE", cert.getEncoded()));
            Files.writeString(fixture.resolve("client.pem"), text);
        }
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }

    private static void generate(String name, String dn, String extension) throws Exception {
        keytool("-genkeypair", "-alias", name, "-dname", dn, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-ext", extension, "-keystore", fixture.resolve(name + ".p12").toString(),
                "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt");
    }

    private static void signed(String name, String dn, String eku) throws Exception {
        generate(name, dn, "eku=" + eku);
        keytool("-certreq", "-alias", name, "-keystore", fixture.resolve(name + ".p12").toString(),
                "-storepass", PASSWORD, "-file", fixture.resolve(name + ".csr").toString());
        keytool("-gencert", "-alias", "ca", "-keystore", fixture.resolve("ca.p12").toString(),
                "-storepass", PASSWORD, "-infile", fixture.resolve(name + ".csr").toString(),
                "-outfile", fixture.resolve(name + ".crt").toString(), "-rfc", "-validity", "2",
                "-ext", "eku=" + eku, "-ext", "san=dns:localhost,ip:127.0.0.1");
        keytool("-importcert", "-alias", "ca", "-keystore", fixture.resolve(name + ".p12").toString(),
                "-storepass", PASSWORD, "-file", fixture.resolve("ca.pem").toString(), "-noprompt");
        keytool("-importcert", "-alias", name, "-keystore", fixture.resolve(name + ".p12").toString(),
                "-storepass", PASSWORD, "-file", fixture.resolve(name + ".crt").toString(), "-noprompt");
    }

    private static void keytool(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool" + (isWindows() ? ".exe" : "")).toString());
        command.addAll(List.of(arguments));
        Path log = fixture.resolve("keytool.log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("keytool timed out"); }
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(log); } catch (Exception e) { return e.toString(); }
        });
    }

    private static boolean isWindows() { return java.io.File.separatorChar == '\\'; }

    private HttpsServer server(boolean requireClient, List<String> received) throws Exception {
        var keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(fixture.resolve("server.p12"))) { keys.load(in, PASSWORD.toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, PASSWORD.toCharArray());
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", keys.getCertificate("ca"));
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trust);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            @Override public void configure(HttpsParameters parameters) {
                var settings = ssl.getDefaultSSLParameters();
                // Schannel/Java mutual-auth TLS 1.3 fails in this environment; fixture negotiates TLS 1.2.
                // Production curl negotiation remains unchanged.
                settings.setProtocols(new String[]{"TLSv1.2"});
                settings.setNeedClientAuth(requireClient);
                parameters.setSSLParameters(settings);
            }
        });
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String peer;
            try { peer = ((com.sun.net.httpserver.HttpsExchange) exchange).getSSLSession().getPeerPrincipal().getName(); }
            catch (SSLPeerUnverifiedException e) { peer = "anonymous"; }
            received.add(peer + " " + exchange.getRequestURI().getPath() + " " + body.length);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private ClientConfig config(HttpsServer server, RequestType type, String certificate, boolean skip) {
        String url = "https://localhost:" + server.getAddress().getPort();
        var certs = new Certificates(fixture.resolve("ca.pem").toString(), fixture.resolve(certificate + ".p12").toString(),
                null, "P12", PASSWORD);
        var args = isWindows() ? List.of("--noproxy", "*", "--ssl-no-revoke") : List.of("--noproxy", "*");
        return new ClientConfig(url + "/tls", "POST", "curl", 5, 5, temp.resolve(skip ? "skipped" : "results").toString(),
                List.of(new PayloadType(ContentType.JSON, TransferEncoding.NA, PayloadSize.SM, null, null, java.util.Map.of(), type)),
                args, java.util.Map.of(), java.util.Map.of(), url + "/mtls", certs);
    }

    @Test void authenticatesClientCertificateForSavedAndSkippedResults() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = server(true, received);
        try {
            for (boolean skip : List.of(false, true)) {
                var run = new BatchExecutor().run(config(server, RequestType.MTLS, "client", skip), message -> {}, 1, skip);
                assertTrue(run.success(), () -> diagnostic(run));
                assertTrue(run.transactions().getFirst().endpointUrl().contains("/mtls/"));
                if (!skip) assertFalse(Files.readString(run.transactions().getFirst().curlLog()).contains(PASSWORD));
            }
            assertEquals(2, received.size());
            assertTrue(received.stream().allMatch(value -> value.startsWith("CN=curlmock-client /mtls/") && value.endsWith("2048")), received.toString());
            assertFalse(Files.exists(temp.resolve("skipped")));
        } finally { server.stop(0); }
    }

    @Test void tlsUsesNormalEndpointAndDoesNotSendConfiguredClientCertificate() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = server(false, received);
        try {
            var run = new BatchExecutor().run(config(server, RequestType.TLS, "client", false));
            assertTrue(run.success(), () -> run.transactions().toString());
            assertEquals(List.of("anonymous /tls/CT_json/TE_NA/PS_SM 2048"), received);
        } finally { server.stop(0); }
    }

    @Test void omittedTypeHttpsCannotRedirectToPlainHttp() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var http = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            received.add("HTTP downgrade");
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        http.start();
        var https = server(false, received);
        https.createContext("/tls", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + http.getAddress().getPort() + "/downgrade");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        try {
            var config = config(https, null, "client", false).withAdditionalCurlArguments(List.of("--location"));
            var run = new BatchExecutor().run(config);
            assertFalse(run.success());
            assertTrue(received.isEmpty(), received.toString());
        } finally { https.stop(0); http.stop(0); }
    }

    private static String diagnostic(BatchExecutor.RunResult run) {
        try { return new String(Files.readAllBytes(run.transactions().getFirst().curlLog()), java.nio.charset.StandardCharsets.ISO_8859_1); }
        catch (Exception e) { return run.transactions().toString(); }
    }

    @Test void mtlsServerRejectsMissingOrUntrustedClientCertificate() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = server(true, received);
        try {
            var tls = new BatchExecutor().run(config(server, RequestType.TLS, "client", false));
            var rogue = new BatchExecutor().run(config(server, RequestType.MTLS, "rogue", false));
            assertFalse(tls.success());
            assertFalse(rogue.success());
            assertTrue(received.isEmpty());
        } finally { server.stop(0); }
    }

    @Test void supportsRsaClientCertificatesWithTls12() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = server(true, received);
        try {
            // Also exercise explicit TLS version arguments with a separate RSA credential.
            var config = config(server, RequestType.MTLS, "rsa-client", false)
                    .withAdditionalCurlArguments(List.of("--tls-max", "1.2"));
            var run = new BatchExecutor().run(config);
            assertTrue(run.success(), () -> diagnostic(run));
            assertTrue(received.getFirst().startsWith("CN=curlmock-rsa-client /mtls/"));
        } finally { server.stop(0); }
    }

    @Test void applicationSingleFilteredAndAllPreserveClientAuthentication() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = server(true, received);
        try {
            ApplicationPanel[] app = new ApplicationPanel[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                app[0] = new ApplicationPanel(config(server, RequestType.MTLS, "client", false));
                RuntimeSummaryTest.set(app[0].summary, "Method", "PUT");
                app[0].execute.doClick();
            });
            await(() -> app[0].execute.isEnabled());
            assertTrue(app[0].result.getText().contains("200"), app[0].result.getText());
            for (boolean filtered : List.of(false, true)) {
                BatchProgressPanel[] panel = new BatchProgressPanel[1];
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    if (filtered) app[0].search.setText("mtls");
                    panel[0] = app[0].createBatch(filtered);
                    panel[0].start();
                });
                await(() -> panel[0].close.isEnabled());
                assertTrue(panel[0].viewResults.isVisible(), panel[0].log.getText());
            }
            assertEquals(3, received.size());
            assertTrue(received.stream().allMatch(value -> value.startsWith("CN=curlmock-client /mtls/")), received.toString());
        } finally { server.stop(0); }
    }

    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean[] ready = {false};
        while (!ready[0] && System.nanoTime() < deadline) {
            Thread.sleep(30);
            javax.swing.SwingUtilities.invokeAndWait(() -> ready[0] = condition.getAsBoolean());
        }
        assertTrue(ready[0], "Swing execution did not finish");
    }
}
