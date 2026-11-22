# 0934: Intervention classification is not configurable in .mason/mason.toml

Task 890: both send-backs in 7 days were class 'other' rather than build_red, test_red or conflict.

Finding: .mason/mason.toml only routes steps (`on_red = "develop"` for test, review and land). It has no key that names a failure class. The build_red/test_red/conflict classes come from MASON, from the test and landing steps. A send-back from the `review` agent step (an agent rejection, not a suite failure) has no such class, so it falls into 'other'. That is the likely cause of both interventions.

Decision: no config change. A real fix is in MASON, which should add a 'review_rejected' class (and a tooling-fault class) and record the reason on each send-back. Left to the MASON repo (brianm998/mason); this repo cannot classify them.
