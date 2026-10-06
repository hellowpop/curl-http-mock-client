# Execution ledger — plan: docs/superpowers/plans/2026-10-06-curl-http-mock-client.md

User instruction: proceed with the best design without questions. This overrides skill approval handoffs.

Initial workspace: supplied empty non-Git project; worktree and Git-based task scripts did not apply during initial implementation. The project is now tracked in Git on master and pushed to the user-specified GitHub repository. Java 21 is available at D:/01.app/java/jdk-21.0.3.

Pre-flight: task 1 produces ClientConfig/Payload; task 2 consumes ClientConfig; task 3 consumes model/Payload; task 4 consumes ConfigFiles/BatchExecutor. Signatures agree with spec.

Execution: native implementation in current session. Final review will be a delegated review as required by executing-plans.

Task 1: complete — payload tests RED (13 assertion failures) → GREEN (13/13); Java 21.
Task 2: complete — codec operations failed before implementation → GREEN; whole suite 32/32, Maven BUILD SUCCESS.
Build environment: Maven uses different remote-repository contexts inside/outside the sandbox. Run Maven with authorized escalation for dependency access; no project design change.

Task 3: complete — initial missing runner produced failing 60-scenario/failure assertions; all four integration tests pass in task4-red.log. Entire 60-scenario actual curl run passes. Windows connection refusal occurs after ~2 seconds; increased only the refusal test timeouts from 1 to 5 seconds, verified with direct curl exit code 7. Production timeout behavior stays configurable.
Task 4: RED — three CLI tests failed on missing behavior (exit code 2); implementing CLI and packaging next.

Task 4: complete — all 39 tests pass; Maven verify builds 23.99 MB shaded JAR. JAR help, both sample formats, both conversions and a 60/60 successful local endpoint run are verified. Samples are saved under samples/; smoke results under verification/results/.
Final review: independent read-only reviewer dispatched per executing-plans/requesting-code-review. Two initial important findings receive RED→GREEN regressions: numeric enum ordinals and raw non-UTF-8 HTTP headers.

Final review outcome: no critical issues; two important findings and two minor findings. All four were reproduced and fixed. The malformed Excel error classification and caller Java environment mutation affect documented requirements, so they were included in the fix pass. No deferred findings; reviewer declined to judge no behaviors.

Final fixes: numeric enum/string ordinals and YAML scalar coercion now rejected by explicit node validation; non-UTF-8 HTTP headers are decoded byte-preservingly and raw files retained; malformed Excel parsing returns configuration exit 2; build.ps1 restores JAVA_HOME/PATH in finally. Regression tests first failed (review-red.log/review-extra-red.log and build script environment check), then passed.

Final verification: Java 21 Maven verify → BUILD SUCCESS, 49 tests, 0 failures/errors/skips. Actual final JAR → 60 transactions, 60 succeeded, 0 failed. Both sample formats and both conversions passed after fixes. Build script environment regression → PASS. Final local server exited after receiving its 60 requests.

Follow-up: Ctrl-C must save partial results (user requested autonomous implementation, no questions).
Design: per-run shutdown hook cancels registered curl and prevents new requests; waits for one final report write, including interrupted active transaction. Result workbook uses staged/atomic replacement; file operations finish before restoring thread interruption.
RED: ShutdownIntegrationTest failed twice because JVM exit lost the workbook.
GREEN: first-request and after-one-completed-request shutdown cases pass; rows, interrupted error and artifact links retained, pending requests not launched.
Final follow-up verification: Maven verify BUILD SUCCESS, 51 tests, 0 failures/errors/skips. New JAR is packaged. Actual Windows terminal Ctrl-C during second request produced workbook with two transactions (one completed, one interrupted), two curl logs, no remaining project curl process. Independent read-only review found no actionable material issues. README and verification documentation updated.

Follow-up: pretty mock request structure and user-specified Excel column order.
RED: six JSON/XML format assertions and one Excel order assertion failed (pretty-columns-red.log).
GREEN: JSON/XML use two spaces and LF; exact byte sizes remain verified. Form and multipart retain their standard wire formats. ResultWorkbook headers/values/links/numeric widths match the specified 11 columns; existing curl/shutdown assertions updated.
Final verification: Maven verify BUILD SUCCESS, 52 tests, no failures/errors/skips. Final JAR actual curl run 60/60 successful. Generated workbook header order and pretty JSON 2048-byte artifact checked directly. Independent read-only review: no substantive findings. README, spec and verification documentation updated.

Follow-up: comma-separated multiple values in payloadTypes fields.
Design: expand choices within each entry in input CT→TE→PS order; preserve entry order and explicit duplicates, strip surrounding whitespace, reject invalid/empty tokens before execution. Both YAML and Excel share expansion; conversion emits expanded records with same semantics. Existing singleton model stays unchanged.
RED: four tests failed on unsupported comma fields (comma-values-red.log).
GREEN: exact eight paths, Excel comma cells, cross-directory bidirectional conversion, mixed entries/duplicates, twelve malformed comma cases, unknown entry fields, and actual eight curl requests all pass.
Final verification: Maven verify BUILD SUCCESS, 69 tests, zero failures/errors/skips. Final JAR example produced 8 successful transactions and converted YAML→Excel→YAML into eight individual records. Independent read-only review found no substantive issues. Added samples/config-multi.yml; README/spec/verification updated.

Follow-up: directly configure payload sizes such as 20K and 1M.
Design: immutable PayloadSize with legacy SM/CM/LG constants and Jackson scalar token mapping. K/M are binary units; B/bare-byte-strings and KB/KiB/MB/MiB aliases supported. Canonical path tokens preserve custom units. Integer range 2KiB–64MiB accommodates all formats and bounds the current in-memory generator; checked long multiplication rejects overflow before allocation. Comma mixes and both config formats share the parser.
RED: 21 missing direct-size cases reproduced (custom-sizes-red.log).
GREEN: parsing aliases/bounds/overflow, eight exact CT/direct-size payloads, config preset/custom mixes and both format conversions, and actual curl24-case custom-size integration pass.
Final verification: Maven verify BUILD SUCCESS, 107 tests, zero failures/errors/skips. Final JAR 24/24 successful transactions, 20K/1M request files exactly 20,480/1,048,576 bytes, YAML→Excel→YAML preserves24 records. Read-only review: no actionable findings. Added samples/config-custom-sizes.yml; README/spec/verification updated.

Follow-up: curl timeout default and per-payload overrides.
Design: global connectTimeoutSeconds/requestTimeoutSeconds default to 3 seconds. Optional per-entry values override each global value independently. CSV expansion and format conversion preserve overrides and omissions. Excel supports two optional timeout columns and legacy three-column sheets. Curl arguments and process wait use the resolved per-entry request timeout.
RED: default tests showed the old 60-second value and delayed requests succeeded; per-entry test rejected the unsupported timeout property.
GREEN: defaults, explicit values, YAML/Excel round-trips, legacy Excel, invalid per-entry timeout values, actual curl timeouts and global inheritance pass.
Final verification: Maven verify BUILD SUCCESS, 120 tests, zero failures/errors/skips. Default curl timeout occurred at about 3.05 seconds; per-entry 1-second limits exited with code 28 while the following inherited 4-second request succeeded. Final JAR converted the per-payload sample in both directions and regenerated the Excel sample. Commit 40a0071 was pushed to origin/master and remote HEAD was confirmed equal to local HEAD.
Documentation follow-up: README now includes timeout precedence and YAML/Excel examples; CHANGELOG records compatibility and previous/new defaults; spec and latest verification summary agree with current implementation and test reports.

Documentation follow-up (2026-10-06): recorded the proposed curl extra-argument feature in the design spec, including YAML curlArguments, Excel Settings JSON-array representation, repeated CLI --curl-arg, merge order, affected components and planned validation. Status: design draft only; no implementation or new test results. Conflicts with application-managed curl options still require a defined rule before implementation.

Follow-up implementation (2026-10-06): completed curlArguments in YAML/Excel and repeated run-only --curl-arg, preserving order and appending CLI values after file values. CurlArguments validates supported options with known arity; rejects managed URL/body/timeout/artifact changes, payload header overrides and malformed tokens. Optional Excel blank means []; nonblank values require strict JSON arrays. Added project.md per AGENTS.md, README examples, samples and changelog.
RED: existing implementation rejected curlArguments and --curl-arg; new round-trip and real-header tests failed. GREEN: all 37 new tests pass. Final Java 21 Maven verify: BUILD SUCCESS, 157 tests, no failures/errors/skips. Final JAR smoke: 3/3 NA/GZ/CSB requests, both config/CLI headers in order, all bodies 2048 bytes after gzip decompression. Read-only review clarified intentional Excel blank-as-empty behavior; no unresolved material findings.
