# 0919: Unit tests for kotlin-desktop and android

- JUnit 4 only, as in shared/ (see 0377); the tested classes are plain objects, so kotlinx-coroutines-test is not needed (StateFlow `.value` reads suffice).
- Both modules get the same always-rerun `testLogging` as shared so the MASON regex parser sees one PASSED line per test.
- Filter-state mapping and smart-collection expansion already live in shared/ and are covered there (0377).
