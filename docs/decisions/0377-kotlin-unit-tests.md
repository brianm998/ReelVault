# 0377: Kotlin unit tests live in shared/

- Reuse JUnit 4 (already a testImplementation dependency); no kotlin-test added.
- Pure logic that sat in client view-models or a gRPC-bound class was extracted to shared (`LocalFilterMatch.kt`, `SmartFilterRewrite.kt`, `toWireValue`) so it is testable without Compose or stubs.
- The MASON suite uses the regex parser on Gradle `PASSED` lines (the test task is set to always rerun), since a results-file glob is not documented for the junit parser.
