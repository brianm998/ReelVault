# 0919: Unit tests for kotlin-desktop and android

- JUnit 4 only, as in shared/ (see 0377); the tested classes are plain objects, so kotlinx-coroutines-test is not needed (StateFlow `.value` reads suffice).
- Both modules get the same always-rerun `testLogging` as shared so the MASON regex parser sees one PASSED line per test.
- The desktop view-model's filter-state-to-smart-collection mapping was extracted to the pure `buildSmartFilters` (GridViewModel.kt) and is tested in `SmartFiltersMappingTest`; SmartCollectionFilters JSON is covered in shared/ (0377). Android's view-models have no equivalent pure mapping to extract, so only NavHistory is tested there.
