# curl HTTP Mock Client Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. The user requested autonomous design and execution without questions; design/plan approval prompts are waived.

**Goal:** Deliver a Java 21 executable JAR that drives curl, converts Excel/YAML configuration, and produces linked transaction reports.

**Architecture:** Immutable configuration records connect two codecs, a payload generator, and a curl process runner. A sequential batch executor persists transaction artifacts and a POI workbook; picocli exposes mutually exclusive operations.

**Tech Stack:** Java 21, Maven Shade, picocli, Jackson YAML/JSON, Apache POI, SLF4J/Logback, JUnit Jupiter.

**Spec:** `docs/superpowers/specs/2026-10-06-curl-http-mock-client-design.md`

## Global Constraints

- Java 21; actual external curl process; HTTP/1.1.
- CT: json/xml/form/multipart; TE: NA/GZ/CSB/CCB/CLB; PS: 2,048/16,384/65,536 bytes before gzip.
- Chunk feeding blocks: 1,024/8,192/32,768 bytes; actual HTTP chunks need not match.
- UUID transaction artifacts, run timestamp+UUID filenames, relative Excel hyperlinks.
- No automatic retries; log/report HTTP failures and continue.
- Work in the supplied empty directory (not a Git repository); no Git worktree or commit is possible. Keep a local progress ledger instead of Git-based scripts.

## Review Focus

- Relative result paths after conversion to a different directory must preserve their destination (task 2).
- Malformed Excel/YAML inputs must fail before a network request (task 2).
- Missing curl, HTTP errors, and timeouts must retain transaction artifacts and report rows (task 3).
- Multipart overhead must be included in the requested body length (task 1 and task 3).
- Mutually exclusive CLI modes and accidental file overwrite must be rejected (task 4).

## Task 1: Payload and common model

Files: `pom.xml`, `src/main/java/dev/curlmock/{ContentType,TransferEncoding,PayloadSize,PayloadType,ClientConfig,Payload,PayloadGenerator}.java`, `src/test/java/dev/curlmock/PayloadGeneratorTest.java`.

Produces: `ClientConfig.sample()`, `ClientConfig.validate()`, `PayloadGenerator.generate(PayloadType): Payload`, `Payload.body(): byte[]`, `Payload.contentType(): String`.

- [ ] Write tests verifying exact body lengths for 12 CT/PS combinations, valid JSON/XML/form/multipart and differing random data.
- [ ] Run Maven test and observe missing behavior; use minimal stubs if needed for a meaningful assertion failure.
- [ ] Implement common validated records and payload serialization with random name/value pairs and a final size-adjusted value.
- [ ] Run `mvn test -Dtest=PayloadGeneratorTest`; expect all tests passing.

## Task 2: YAML/Excel config codecs

Files: `src/main/java/dev/curlmock/{ConfigFiles,ExcelConfigCodec}.java`, `src/test/java/dev/curlmock/ConfigFilesTest.java`.

Consumes: `ClientConfig` and `PayloadType` from task 1.
Produces: `ConfigFiles.read(Path): ClientConfig`, `ConfigFiles.write(Path, ClientConfig, boolean): void`, `ConfigFiles.convert(Path, Path, boolean): void`.

- [ ] Write tests for 60-entry cross-directory round-trips, defaults, invalid enum/URL/timeouts, duplicate keys/rows, formula cells, missing sheets, unknown keys, empty arrays, and no overwrite.
- [ ] Run the tests and observe missing codec behavior.
- [ ] Implement strict YAML and typed Excel reading/writing, path resolution and conversion with POI styles.
- [ ] Run `mvn test`; expect all tests passing.

## Task 3: Actual curl execution and report

Files: `src/main/java/dev/curlmock/{TransactionResult,CurlRunner,BatchExecutor,ResultWorkbook}.java`, `src/main/resources/logback.xml`, `src/test/java/dev/curlmock/CurlIntegrationTest.java`.

Consumes: task 1 payload/model APIs.
Produces: `BatchExecutor.run(ClientConfig): BatchExecutor.RunResult`, containing workbook path, artifacts path, and transaction list.

- [ ] Write real local HTTP-server tests for all 60 scenarios, gzip inflation, headers/path/body sizes, report links, failure status, missing curl, binary response and timeout.
- [ ] Run the tests and observe missing runner behavior.
- [ ] Implement shell-free ProcessBuilder execution, concurrent stdin/diagnostic draining, timeouts, raw response files, gzip entity file, actual header extraction, and result workbook.
- [ ] Run `mvn test`; expect all tests passing with actual curl.

## Task 4: CLI, executable JAR, samples and documentation

Files: `src/main/java/dev/curlmock/Main.java`, `src/test/java/dev/curlmock/MainTest.java`, `README.md`, `build.ps1`, `.gitignore`, `samples/config.yml`, `samples/config.xlsx`.

Consumes: task 2 codec and task 3 executor APIs.
Produces: CLI options defined in spec, exit codes 0/1/2, executable shaded JAR and samples.

- [ ] Write tests for sample generation, both conversions, conflicting options, protected outputs, help and run mode.
- [ ] Run the tests and observe missing CLI behavior.
- [ ] Implement picocli options, validation and clear error messages; document Korean usage and dependency sources.
- [ ] Run `mvn verify`, then real `java -jar` help, sample generation, both conversions, and a local endpoint execution.
- [ ] Generate deliverable samples and record verification in the ledger; perform final review and address material findings.
