package dev.curlmock;

import java.util.concurrent.Callable;
import java.nio.file.Path;
import java.io.IOException;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "curl-http-mock-client", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Java 21 curl API mock client: run scenarios, generate samples, convert Excel/YAML.")
public final class Main implements Callable<Integer> {
    @ArgGroup(exclusive = true, multiplicity = "1") private Mode mode;
    @Option(names = "--output", description = "Conversion destination (.yml/.yaml/.xlsx).") private Path output;
    @Option(names = "--overwrite", description = "Replace an existing sample, converted configuration or JMX export.") private boolean overwrite;
    @Option(names = "--application", description = "Open the Swing application; requires --config.") private boolean application;
    @Option(names = "--curl-arg", paramLabel = "ARG", description = "Append one curl argument (repeatable, requires --config; JMX export supports a subset; use --curl-arg=ARG).")
    private java.util.List<String> curlArguments = new java.util.ArrayList<>();
    @Spec private Model.CommandSpec spec;
    @Option(names = "--skip-result", description = "Skip result workbook and all request/response artifacts (CLI run only).")
    private boolean skipResult;
    @Option(names = "--loop", paramLabel = "N", description = "Repeat the complete request sequence N times (positive integer; default: 1, CLI run only).")
    private Integer loop;
    @Option(names = "--delay", paramLabel = "MS", description = "Wait MS milliseconds between units, including loop boundaries (non-negative integer; default: 0, CLI run only).")
    private Long delay;
    @Option(names = "--export-jmx", paramLabel = "FILE", description = "Export configured requests to a self-contained Apache JMeter .jmx file; requires --config.")
    private Path exportJmx;

    static final class Mode {
        @Option(names = "--config", description = "Execute payloadTypes from a YAML or Excel configuration.") Path config;
        @Option(names = "--sample-excel", description = "Write a sample Excel configuration with all preset combinations.") Path sampleExcel;
        @Option(names = "--sample-yml", description = "Write a sample YAML configuration with all preset combinations.") Path sampleYml;
        @Option(names = "--excel-to-yml", description = "Convert Excel configuration to YAML; requires --output.") Path excelToYml;
        @Option(names = "--yml-to-excel", description = "Convert YAML configuration to Excel; requires --output.") Path ymlToExcel;
    }

    public static void main(String[] args) {
        var main = new Main();
        int exit = new CommandLine(main).execute(args);
        // Keep the Swing event thread alive after the window has been opened.
        if (!main.application || exit != 0) System.exit(exit);
    }

    @Override public Integer call() {
        ClientConfig config;
        try {
            boolean conversion = mode.excelToYml != null || mode.ymlToExcel != null;
            if (exportJmx != null && (mode.config == null || application || skipResult || loop != null || delay != null))
                throw new IllegalArgumentException("--export-jmx requires --config without --application, --skip-result, --loop or --delay");
            if ((skipResult || loop != null || delay != null) && (mode.config == null || application))
                throw new IllegalArgumentException("--skip-result, --loop and --delay require --config without --application");
            if (loop != null && loop < 1) throw new IllegalArgumentException("--loop must be a positive integer");
            if (delay != null && delay < 0) throw new IllegalArgumentException("--delay must be a non-negative integer in milliseconds");
            if (application && mode.config == null) throw new IllegalArgumentException("--application requires --config");
            if (mode.config == null && !curlArguments.isEmpty()) throw new IllegalArgumentException("--curl-arg is only valid with --config");
            if (conversion && output == null) throw new IllegalArgumentException("Conversion requires --output");
            if (!conversion && output != null) throw new IllegalArgumentException("--output is only valid for conversion");
            if (mode.config != null && overwrite && exportJmx == null) throw new IllegalArgumentException("--overwrite is only valid for samples, conversion or JMX export");
            if (mode.sampleExcel != null) {
                requireFormat(mode.sampleExcel, true);
                ConfigFiles.write(mode.sampleExcel, ClientConfig.sample(), overwrite);
                return created(mode.sampleExcel);
            }
            if (mode.sampleYml != null) {
                requireFormat(mode.sampleYml, false);
                ConfigFiles.write(mode.sampleYml, ClientConfig.sample(), overwrite);
                return created(mode.sampleYml);
            }
            if (conversion) {
                Path input = mode.excelToYml != null ? mode.excelToYml : mode.ymlToExcel;
                requireFormat(input, mode.excelToYml != null);
                requireFormat(output, mode.ymlToExcel != null);
                ConfigFiles.convert(input, output, overwrite);
                return created(output);
            }
            config = ConfigFiles.read(mode.config);
            config = config.withAdditionalCurlArguments(curlArguments);
            config.validate();
            if (exportJmx != null) {
                JmxExporter.write(exportJmx, config, overwrite);
                return created(exportJmx);
            }
        } catch (IOException | RuntimeException e) {
            spec.commandLine().getErr().println("Configuration error: " + e.getMessage());
            return 2;
        }
        try {
            if (application) {
                ApplicationPanel.open(config);
                return 0;
            }
            var run = new BatchExecutor().run(config, message -> {}, loop == null ? 1 : loop, skipResult, delay == null ? 0 : delay);
            if (skipResult) spec.commandLine().getOut().println("Result saving skipped.");
            else {
                spec.commandLine().getOut().println("Results: " + run.workbook());
                spec.commandLine().getOut().println("Artifacts: " + run.artifactsDirectory());
            }
            long successes = run.transactions().stream().filter(TransactionResult::success).count();
            spec.commandLine().getOut().printf("Transactions: %d; succeeded: %d; failed: %d%n",
                    run.transactions().size(), successes, run.transactions().size() - successes);
            if (run.interrupted()) spec.commandLine().getOut().println(skipResult
                    ? "Run interrupted; result saving skipped." : "Run interrupted; partial results saved.");
            return run.success() ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            spec.commandLine().getErr().println("Execution error: " + e.getMessage());
            return 1;
        }
    }

    private int created(Path path) {
        spec.commandLine().getOut().println("Created: " + path.toAbsolutePath().normalize());
        return 0;
    }
    private static void requireFormat(Path path, boolean excel) {
        if (ConfigFiles.isExcel(path) != excel) throw new IllegalArgumentException("Expected " + (excel ? ".xlsx" : ".yml/.yaml") + " file: " + path);
    }
}
