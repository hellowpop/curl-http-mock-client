package dev.curlmock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BatchExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(BatchExecutor.class);
    public RunResult run(ClientConfig config) throws IOException {
        return run(config, message -> {});
    }

    public RunResult run(ClientConfig config, java.util.function.Consumer<String> progress) throws IOException {
        config.validate();
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")) + "_" + UUID.randomUUID();
        Path root = Path.of(config.outputDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path artifacts = Files.createDirectory(root.resolve(runId));
        Path workbook = root.resolve(runId + ".xlsx");
        var results = new ArrayList<TransactionResult>();
        var generator = new PayloadGenerator();
        var runner = new CurlRunner();
        var control = new RunControl();
        Thread shutdownHook = new Thread(() -> {
            control.cancel();
            LOG.warn("Shutdown requested; stopping curl and saving results: {}", workbook);
            control.awaitCompletion();
        }, "curlmock-save-results");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        Throwable failure = null;
        try {
            LOG.info("Starting {} transactions; artifacts={}", config.payloadTypes().size(), artifacts);
            progress.accept("Starting " + config.payloadTypes().size() + " transactions; artifacts=" + artifacts);
            for (var type : config.payloadTypes()) {
                if (Thread.currentThread().isInterrupted()) control.cancel();
                if (control.isCancelled()) break;
                String uuid = UUID.randomUUID().toString();
                progress.accept("실행 " + (results.size() + 1) + "/" + config.payloadTypes().size() + " " + type.path() + " uuid=" + uuid);
                var result = runner.execute(config, type, generator.generate(type), uuid, artifacts, control);
                results.add(result);
                LOG.info("{}/{} {} {} status={} curl={} elapsed={}ms", results.size(), config.payloadTypes().size(), uuid,
                        type.path(), result.httpStatus(), result.curlExitCode(), result.elapsedMs());
                if (!result.success()) LOG.warn("{}: {}", uuid, result.error());
                progress.accept("완료 " + results.size() + "/" + config.payloadTypes().size() + " " + type.path()
                        + " status=" + result.httpStatus() + " curl=" + result.curlExitCode() + " elapsed=" + result.elapsedMs() + "ms"
                        + (result.success() ? "" : " error=" + result.error()));
                if (Thread.currentThread().isInterrupted()) control.cancel();
            }
        } catch (IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            // Clear interrupt state while writing: interruptible file channels otherwise fail.
            boolean restoreInterrupt = Thread.interrupted();
            try {
                ResultWorkbook.write(workbook, results);
                LOG.info("Result workbook: {} ({} recorded transactions)", workbook, results.size());
                progress.accept("Result workbook: " + workbook + " (" + results.size() + " recorded transactions)");
            } catch (IOException | RuntimeException reportError) {
                LOG.error("Cannot save result workbook: {}", workbook, reportError);
                if (failure != null) failure.addSuppressed(reportError);
                else throw reportError;
            } finally {
                control.complete();
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException ignored) { /* JVM shutdown is already in progress. */ }
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
        return new RunResult(workbook, artifacts, List.copyOf(results), control.isCancelled());
    }
    public record RunResult(Path workbook, Path artifactsDirectory, List<TransactionResult> transactions, boolean interrupted) {
        public boolean success() { return !interrupted && !transactions.isEmpty() && transactions.stream().allMatch(TransactionResult::success); }
    }
}
