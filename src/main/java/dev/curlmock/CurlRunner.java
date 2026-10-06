package dev.curlmock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

final class CurlRunner {
    private static final Pattern STATUS = Pattern.compile("CURLMOCK_HTTP_STATUS:(\\d{3})");

    TransactionResult execute(ClientConfig config, PayloadType type, Payload payload, String uuid, Path directory, RunControl control) throws IOException {
        long start = System.nanoTime();
        Path log = artifact(directory, uuid, "curl_log.txt");
        Path request = artifact(directory, uuid, "request_payload.txt");
        Path response = artifact(directory, uuid, "response_payload.txt");
        Path requestHeadersFile = artifact(directory, uuid, "request_headers.txt");
        Path responseHeadersFile = artifact(directory, uuid, "response_headers.txt");
        Path stdoutFile = artifact(directory, uuid, "curl_stdout.txt");
        Files.write(request, payload.body());
        Files.write(response, new byte[0]);
        Files.writeString(responseHeadersFile, "");
        Files.writeString(stdoutFile, "");
        Path wireBody = request;
        if (type.transferEncoding() == TransferEncoding.GZ) {
            wireBody = artifact(directory, uuid, "request_payload.gz");
            try (var gzip = new GZIPOutputStream(Files.newOutputStream(wireBody))) { gzip.write(payload.body()); }
        }
        String url = config.endpointUrl().replaceAll("/+$", "") + type.path();
        int connectTimeout = type.effectiveConnectTimeoutSeconds(config);
        int requestTimeout = type.effectiveRequestTimeoutSeconds(config);
        var command = new ArrayList<>(List.of(config.curlExecutable(), "--disable", "--silent", "--show-error", "--verbose",
                "--http1.1", "--globoff", "--request", config.method(), "--url", url,
                "--connect-timeout", Integer.toString(connectTimeout), "--max-time", Integer.toString(requestTimeout),
                "--output", response.toString(), "--dump-header", responseHeadersFile.toString(),
                "--write-out", "\nCURLMOCK_HTTP_STATUS:%{http_code}\n", "--header", "Content-Type: " + payload.contentType(), "--header", "Expect:"));
        if (type.transferEncoding().chunked()) command.addAll(List.of("--header", "Transfer-Encoding: chunked", "--header", "Content-Length:", "--upload-file", "-"));
        else {
            if (type.transferEncoding() == TransferEncoding.GZ) command.addAll(List.of("--header", "Content-Encoding: gzip"));
            command.addAll(List.of("--data-binary", "@" + wireBody));
        }
        command.addAll(config.curlArguments());
        Files.writeString(log, "uuid: " + uuid + "\nendpoint: " + url + "\nrequest entity file: " + wireBody
                + "\nresponse entity file: " + response + "\nstdin block bytes: " + type.transferEncoding().blockBytes()
                + "\nCommand arguments (JSON): " + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(command)
                + "\n\n--- curl stderr / verbose ---\n", StandardCharsets.UTF_8);
        int exit = -1;
        String error = "";
        boolean restoreInterrupt = false;
        Process process = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                process = control.start(new ProcessBuilder(command).redirectOutput(stdoutFile.toFile())
                        .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile())));
                if (process != null) {
                    Process active = process;
                    var writer = executor.submit(() -> {
                        try (var out = active.getOutputStream()) {
                            if (type.transferEncoding().chunked()) {
                                byte[] bytes = payload.body();
                                int block = type.transferEncoding().blockBytes();
                                for (int offset = 0; offset < bytes.length; offset += block) {
                                    out.write(bytes, offset, Math.min(block, bytes.length - offset));
                                    out.flush();
                                }
                            }
                        }
                        return null;
                    });
                    if (!process.waitFor((long) requestTimeout + 5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor();
                        exit = 28;
                        error = "curl process timed out";
                    } else exit = process.exitValue();
                    try { writer.get(2, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.ExecutionException e) {
                        if (exit == 0) error = "Failed to supply curl stdin: " + e.getCause().getMessage();
                    } catch (java.util.concurrent.TimeoutException e) {
                        writer.cancel(true);
                        error = "curl stdin writer timed out";
                    }
                }
            } catch (IOException e) {
                error = "Cannot execute curl: " + e.getMessage();
            } catch (InterruptedException e) {
                restoreInterrupt = true;
                control.cancel();
                error = "curl execution interrupted";
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    boolean waiting = true;
                    while (waiting) {
                        try { process.waitFor(); waiting = false; }
                        catch (InterruptedException e) { restoreInterrupt = true; }
                    }
                }
                control.processFinished(process);
            }
        }
        if (control.isCancelled()) error = "curl execution interrupted by cancellation/shutdown";
        try {
            String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
            int status = 0;
            var matcher = STATUS.matcher(stdout);
            while (matcher.find()) status = Integer.parseInt(matcher.group(1));
            if (error.isEmpty() && exit != 0) error = "curl exited with code " + exit + "; see curl log";
            if (error.isEmpty() && (status < 200 || status >= 400)) error = "HTTP status " + status;
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            // HTTP/1.1 header values may contain raw obs-text bytes (0x80..0xff).
            // Preserve the raw diagnostic file and decode header lines without UTF-8 validation.
            String verbose = new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1);
            String requestHeaders = verbose.lines().filter(line -> line.startsWith("> "))
                    .map(line -> line.substring(2)).collect(java.util.stream.Collectors.joining("\n"));
            Files.writeString(requestHeadersFile, requestHeaders, StandardCharsets.UTF_8);
            String responseHeaders = new String(Files.readAllBytes(responseHeadersFile), StandardCharsets.ISO_8859_1);
            Files.writeString(log, "\n--- curl stdout ---\n" + stdout + "\ncurl exit code: " + exit
                    + "\nhttp status: " + status + "\nelapsed ms: " + elapsed + "\nerror: " + error + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            Files.deleteIfExists(stdoutFile);
            return new TransactionResult(uuid, url, log, requestHeaders, request, responseHeaders, response, status, exit, elapsed, error);
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    static Path artifact(Path directory, String uuid, String suffix) { return directory.resolve(uuid + "_" + suffix); }
}
