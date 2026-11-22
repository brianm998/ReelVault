# 0933: No quarantine for the digest's flaky tests

Task 889 reports ~40 flaky "cargo-workspace" tests. A search of this repo finds none of the named tests
(`a_push_reaches_owner_devices`, `a_skipped_batch_whose_*`, `landing_floor*`). They belong to MASON's own
workspace, whose digest was attributed to this project. ReelVault's `core-test` suite (`.mason/mason.toml`)
has no known flakes, so quarantining or retrying here would only hide real failures.

Decision: change no suites. Fix the flakes in the MASON repo. The analyst should attribute digests to the
project whose workspace ran the tests.
