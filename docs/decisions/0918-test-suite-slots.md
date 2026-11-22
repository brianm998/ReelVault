# 0918: test-suite slots 1 -> 2

Task 400 accounted for all cpu/compile/exclusive wait. A single test-suite slot serialised heavy work behind it. Chose 2 slots (not higher) to keep CPU contention moderate; revisit if wait_share stays high.
