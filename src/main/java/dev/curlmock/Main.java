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
    @Option(names = "--overwrite", description = "Replace an existing sample or converted configuration.") private boolean overwrite;
    @Option(names = "--application", description = "Open the Swing application; requires --config.") private boolean application;
    @Option(names = "--curl-arg", paramLabel = "ARG", description = "Append one curl argument (repeatable, run mode only; use --curl-arg=ARG).")
    private java.util.List<String> curlArguments = new java.util.ArrayList<>();
    @Spec private Model.CommandSpec spec;

    static final class Mode {
        @Option(names = "--config", description = "Execute payloadTypes from a YAML or Excel configuration.") Path config;
        @Option(names = "--sample-excel", description = "Write a 60-scenario sample Excel configuration.") Path sampleExcel;
        @Option(names = "--sample-yml", description = "Write a 60-scenario sample YAML configuration.") Path sampleYml;
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
            if (application && mode.config == null) throw new IllegalArgumentException("--application requires --config");
            if (mode.config == null && !curlArguments.isEmpty()) throw new IllegalArgumentException("--curl-arg is only valid with --config");
            if (conversion && output == null) throw new IllegalArgumentException("Conversion requires --output");
            if (!conversion && output != null) throw new IllegalArgumentException("--output is only valid for conversion");
            if (mode.config != null && overwrite) throw new IllegalArgumentException("--overwrite is only valid for samples or conversion");
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
        } catch (IOException | RuntimeException e) {
            spec.commandLine().getErr().println("Configuration error: " + e.getMessage());
            return 2;
        }
        try {
            if (application) {
                ApplicationPanel.open(config);
                return 0;
            }
            var run = new BatchExecutor().run(config);
            spec.commandLine().getOut().println("Results: " + run.workbook());
            spec.commandLine().getOut().println("Artifacts: " + run.artifactsDirectory());
            long successes = run.transactions().stream().filter(TransactionResult::success).count();
            spec.commandLine().getOut().printf("Transactions: %d; succeeded: %d; failed: %d%n",
                    run.transactions().size(), successes, run.transactions().size() - successes);
            if (run.interrupted()) spec.commandLine().getOut().println("Run interrupted; partial results saved.");
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
