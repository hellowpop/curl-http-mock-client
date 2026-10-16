package dev.curlmock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TlsConfigTest {
    @TempDir Path temp;
    private ClientConfig read(String settings, String type) throws Exception {
        Path source = temp.resolve("source/config.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "endpointUrl: https://localhost:8443/api\n" + settings
                + "payloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM"
                + (type == null ? "" : ", requestType: '" + type + "'") + "}]\n");
        return ConfigFiles.read(source);
    }

    @Test void expandsRequestTypesAndPreservesCertificatePathsAcrossFormatsAndCopies() throws Exception {
        var config = read("mtlsEndpointUrl: https://localhost:9443/secure\ncertificates:\n"
                + "  caCertificate: certs/ca.pem\n  clientCertificate: certs/client.pem\n"
                + "  clientKey: certs/client.key\n  certificateType: PEM\n  keyPassword: 'p:a ss'\n", "tls,mtls");
        assertEquals(2, config.payloadTypes().size());
        var node = ConfigFiles.MAPPER.valueToTree(config);
        assertEquals("TLS", node.at("/payloadTypes/0/requestType").asText());
        assertEquals("MTLS", node.at("/payloadTypes/1/requestType").asText());
        assertEquals(temp.resolve("source/certs/client.pem").toString(), node.at("/certificates/clientCertificate").asText());
        Path excel = temp.resolve("elsewhere/config.xlsx"), yaml = temp.resolve("back/config.yml");
        ConfigFiles.write(excel, config, false);
        ConfigFiles.convert(excel, yaml, false);
        assertEquals(config, ConfigFiles.read(yaml));
        assertEquals(node.get("certificates"), ConfigFiles.MAPPER.valueToTree(config.withAdditionalCurlArguments(List.of("--noproxy", "*"))).get("certificates"));
    }

    @Test void routesTlsAndMtlsSeparatelyAndDoesNotLogKeyPassword() throws Exception {
        var config = read("mtlsEndpointUrl: https://localhost:9443/secure\ncurlExecutable: missing-curl-test\n"
                + "certificates: {clientCertificate: client.p12, certificateType: P12, keyPassword: top-secret}\n", "TLS,MTLS");
        Files.write(temp.resolve("source/client.p12"), new byte[]{1});
        var run = new BatchExecutor().run(config);
        assertEquals("https://localhost:8443/api/CT_json/TE_NA/PS_SM", run.transactions().get(0).endpointUrl());
        assertEquals("https://localhost:9443/secure/CT_json/TE_NA/PS_SM", run.transactions().get(1).endpointUrl());
        String tls = Files.readString(run.transactions().get(0).curlLog());
        String mtls = Files.readString(run.transactions().get(1).curlLog());
        assertFalse(tls.contains("\"--cert\""));
        assertTrue(mtls.contains("\"--cert\""));
        assertTrue(mtls.contains("\"P12\""));
        assertFalse(mtls.contains("top-secret"));
    }

    @Test void pemConfigurationSuppliesKeyAndCaForMtls() throws Exception {
        var config = read("mtlsEndpointUrl: https://localhost:9443\ncurlExecutable: missing-curl-test\n"
                + "certificates: {caCertificate: ca.pem, clientCertificate: client.pem, clientKey: client.key}\n", "MTLS");
        for (String file : List.of("ca.pem", "client.pem", "client.key")) Files.writeString(temp.resolve("source/" + file), "fixture");
        var run = new BatchExecutor().run(config);
        String log = Files.readString(run.transactions().getFirst().curlLog());
        assertTrue(log.contains("\"--cert-type\",\"PEM\""));
        assertTrue(log.contains("\"--key\""));
        assertTrue(log.contains("\"--cacert\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "mtlsEndpointUrl: http://localhost:9443\ncertificates: {clientCertificate: client.p12, certificateType: P12}\n",
        "mtlsEndpointUrl: https://localhost:9443\n",
        "certificates: {clientCertificate: client.p12, certificateType: P12}\n",
        "mtlsEndpointUrl: https://localhost:9443\ncertificates: {clientCertificate: client.pem}\n",
        "mtlsEndpointUrl: https://localhost:9443\ncertificates: {clientCertificate: client.p12, certificateType: BAD}\n",
        "mtlsEndpointUrl: https://localhost:9443\ncertificates: {clientCertificate: 12, certificateType: P12}\n",
        "mtlsEndpointUrl: https://localhost:9443\ncertificates: {clientCertificate: client.p12, certificateType: P12, unknown: value}\n"
    })
    void rejectsInvalidMtlsSettings(String settings) {
        assertThrows(Exception.class, () -> read(settings, "MTLS"));
    }

    @Test void rejectsCertificateOverridesAndMissingFilesBeforeCreatingResults() throws Exception {
        String settings = "mtlsEndpointUrl: https://localhost:9443\ncertificates: {clientCertificate: client.p12, certificateType: P12}\n";
        var config = read(settings, "MTLS");
        assertThrows(IllegalArgumentException.class, () -> config.withAdditionalCurlArguments(List.of("--cert", "other.p12")).validate());
        assertThrows(Exception.class, () -> new BatchExecutor().run(config));
        assertFalse(Files.exists(Path.of(config.outputDirectory())));
    }

    @Test void rejectsExplicitTlsOnHttpAndDefaultsNewSamplesToHttps() throws Exception {
        assertTrue(ClientConfig.sample().endpointUrl().startsWith("https://"));
        assertThrows(Exception.class, () -> read("", "UNKNOWN"));
        Path source = temp.resolve("http.yml");
        Files.writeString(source, "endpointUrl: http://localhost:8080\npayloadTypes: [{contentType: json, transferEncoding: NA, payloadSize: SM, requestType: TLS}]\n");
        assertThrows(Exception.class, () -> ConfigFiles.read(source));
    }

    @Test void applicationRetainsCertificateSettingsInEditedAndFilteredSnapshots() throws Exception {
        var config = read("mtlsEndpointUrl: https://localhost:9443/secure\ncertificates: {clientCertificate: client.p12, certificateType: P12}\n", "TLS,MTLS");
        SwingUtilities.invokeAndWait(() -> {
            var app = new ApplicationPanel(config);
            app.requests.setSelectedIndex(1);
            assertEquals("MTLS", RuntimeSummaryTest.value(app.summary, "Request type"));
            assertEquals("https://localhost:9443/secure/CT_json/TE_NA/PS_SM", RuntimeSummaryTest.value(app.summary, "URL"));
            RuntimeSummaryTest.set(app.summary, "Method", "PUT");
            app.updateSummary.doClick();
            assertEquals("MTLS", RuntimeSummaryTest.value(app.summary, "Request type"));
            app.search.setText("mtls");
            assertEquals(1, app.requests.getModel().getSize());
            assertNotNull(app.createBatch(true));
        });
    }

    @Test void jmxRejectsMtlsInsteadOfExportingWithoutClientAuthentication() throws Exception {
        var config = read("mtlsEndpointUrl: https://localhost:9443\ncertificates: {clientCertificate: client.p12, certificateType: P12}\n", "MTLS");
        Path output = temp.resolve("plan.jmx");
        assertThrows(IllegalArgumentException.class, () -> JmxExporter.write(output, config, false));
        assertFalse(Files.exists(output));
    }
}
