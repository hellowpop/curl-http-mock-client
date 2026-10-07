package dev.curlmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/** A portable test plan with literal request data and no dependency on this application at run time. */
public final class JmxExporter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private JmxExporter() {}

    public static void write(Path destination, ClientConfig config, boolean overwrite) throws IOException {
        config.validate();
        Path path = destination.toAbsolutePath().normalize();
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jmx"))
            throw new IllegalArgumentException("JMX export requires a .jmx file: " + destination);
        if (!overwrite && Files.exists(path)) throw new FileAlreadyExistsException(path.toString());
        // Validate the entire curl option set before creating the destination or generating payloads.
        Options options = options(config);
        String script;
        try (var resource = JmxExporter.class.getResourceAsStream("/jmx-sampler.groovy")) {
            if (resource == null) throw new IOException("Missing JMX sampler resource");
            script = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.createDirectories(path.getParent());
        Path staging = Files.createTempFile(path.getParent(), ".curlmock-jmx-", ".tmp");
        try {
            try (var output = Files.newOutputStream(staging)) {
                var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8");
                try { writePlan(xml, config, options, script); }
                finally { xml.close(); }
            } catch (XMLStreamException e) { throw new IOException("Cannot write JMeter test plan", e); }
            if (overwrite) Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(staging, path);
        } finally { Files.deleteIfExists(staging); }
    }

    private static void writePlan(XMLStreamWriter xml, ClientConfig config, Options options, String script)
            throws XMLStreamException, IOException {
        xml.writeStartDocument("UTF-8", "1.0");
        xml.writeStartElement("jmeterTestPlan");
        xml.writeAttribute("version", "1.2");
        xml.writeAttribute("properties", "5.0");
        xml.writeAttribute("jmeter", "5.5");
        xml.writeStartElement("hashTree");
        element(xml, "TestPlan", "TestPlanGui", "curl HTTP mock client");
        property(xml, "stringProp", "TestPlan.comments", "Exported request bodies are fixed snapshots. One thread, one pass; edit the Thread Group to change load.");
        property(xml, "boolProp", "TestPlan.functional_mode", "false");
        property(xml, "boolProp", "TestPlan.serialize_threadgroups", "false");
        xml.writeEndElement();
        xml.writeStartElement("hashTree");
        element(xml, "ThreadGroup", "ThreadGroupGui", "Configured requests");
        property(xml, "stringProp", "ThreadGroup.on_sample_error", "continue");
        xml.writeStartElement("elementProp");
        xml.writeAttribute("name", "ThreadGroup.main_controller");
        xml.writeAttribute("elementType", "LoopController");
        xml.writeAttribute("guiclass", "LoopControlPanel");
        xml.writeAttribute("testclass", "LoopController");
        xml.writeAttribute("enabled", "true");
        property(xml, "boolProp", "LoopController.continue_forever", "false");
        property(xml, "stringProp", "LoopController.loops", "1");
        xml.writeEndElement();
        property(xml, "stringProp", "ThreadGroup.num_threads", "1");
        property(xml, "stringProp", "ThreadGroup.ramp_time", "1");
        property(xml, "boolProp", "ThreadGroup.scheduler", "false");
        xml.writeEndElement();
        xml.writeStartElement("hashTree");
        int index = 0;
        var generator = new PayloadGenerator();
        for (var type : config.payloadTypes()) {
            Payload payload = generator.generate(type);
            byte[] body = type.transferEncoding().contentEncoding() == null ? payload.body()
                    : RequestCompression.encode(type.transferEncoding(), payload.body());
            var headers = new LinkedHashMap<>(options.headers());
            RequestHeaders.merge(config.headers(), type.headers()).forEach((name, value) -> replace(headers, name, value));
            replace(headers, "Content-Type", payload.contentType());
            if (type.transferEncoding().contentEncoding() != null)
                replace(headers, "Content-Encoding", type.transferEncoding().contentEncoding());
            var data = new LinkedHashMap<String, Object>();
            data.put("url", config.endpointUrl().replaceAll("/+$", "") + type.path());
            data.put("method", config.method());
            data.put("connectTimeout", milliseconds(type.effectiveConnectTimeoutSeconds(config)));
            data.put("requestTimeout", milliseconds(type.effectiveRequestTimeoutSeconds(config)));
            data.put("chunkBytes", type.transferEncoding().blockBytes());
            data.put("headers", headers);
            data.put("insecure", options.insecure());
            data.put("body", Base64.getEncoder().encodeToString(body));
            element(xml, "JSR223Sampler", "TestBeanGUI", (++index) + " " + config.method() + " " + type.path());
            property(xml, "stringProp", "scriptLanguage", "groovy");
            property(xml, "stringProp", "parameters", Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(data)));
            property(xml, "stringProp", "filename", "");
            property(xml, "stringProp", "cacheKey", "true");
            property(xml, "stringProp", "script", script);
            xml.writeEndElement();
            xml.writeEmptyElement("hashTree");
        }
        for (int i = 0; i < 4; i++) xml.writeEndElement();
        xml.writeEndDocument();
        xml.flush();
    }

    private static long milliseconds(int seconds) {
        long millis = seconds * 1000L;
        if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("JMX timeout must not exceed 2147483 seconds");
        return millis;
    }

    private static void element(XMLStreamWriter xml, String type, String gui, String name) throws XMLStreamException {
        xml.writeStartElement(type);
        xml.writeAttribute("guiclass", gui);
        xml.writeAttribute("testclass", type);
        xml.writeAttribute("testname", name);
        xml.writeAttribute("enabled", "true");
    }

    private static void property(XMLStreamWriter xml, String type, String name, String value) throws XMLStreamException {
        xml.writeStartElement(type);
        xml.writeAttribute("name", name);
        xml.writeCharacters(value);
        xml.writeEndElement();
    }

    private record Options(Map<String, String> headers, boolean insecure) {}

    private static Options options(ClientConfig config) {
        var headers = new LinkedHashMap<String, String>();
        boolean insecure = false;
        var args = config.curlArguments();
        for (int i = 0; i < args.size(); i++) {
            String option = args.get(i);
            switch (option) {
                case "--insecure", "-k" -> insecure = true;
                case "--basic" -> { }
                case "--header", "-H" -> {
                    String value = args.get(++i);
                    int colon = value.indexOf(':');
                    int separator = colon >= 0 ? colon : value.indexOf(';');
                    String name = value.substring(0, separator);
                    if (headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name)))
                        throw new IllegalArgumentException("JMX export does not support duplicate curl header: " + name);
                    String content = value.substring(separator + 1).strip();
                    if (colon >= 0 && content.isEmpty())
                        throw new IllegalArgumentException("JMX export does not support curl header suppression: " + name);
                    headers.put(name, content);
                }
                case "--user-agent", "-A" -> replace(headers, "User-Agent", args.get(++i));
                case "--referer", "-e" -> replace(headers, "Referer", args.get(++i));
                case "--oauth2-bearer" -> replace(headers, "Authorization", "Bearer " + args.get(++i));
                case "--user", "-u" -> {
                    String credentials = args.get(++i);
                    if (!credentials.contains(":")) throw new IllegalArgumentException("JMX --user requires literal user:password");
                    replace(headers, "Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
                }
                case "--cookie", "-b" -> {
                    String cookie = args.get(++i);
                    if (!cookie.contains("=")) throw new IllegalArgumentException("JMX export supports literal cookies only, not cookie files");
                    replace(headers, "Cookie", cookie);
                }
                case "--noproxy" -> {
                    if (!args.get(++i).equals("*")) throw new IllegalArgumentException("JMX --noproxy supports only '*'");
                }
                default -> throw new IllegalArgumentException("Unsupported curl option for JMX export: " + option);
            }
        }
        return new Options(headers, insecure);
    }

    private static void replace(Map<String, String> headers, String name, String value) {
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        headers.put(name, value);
    }
}
