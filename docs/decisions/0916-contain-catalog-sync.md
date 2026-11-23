# 0916: Contain catalog sync behind a flag

Sync could not transfer anything yet showed "synced". Until O2 lands, one flag per language (kit, shared) hides all entry points and disables auto-sync. The Swift scaffold throws `SyncError.notAvailable` rather than marking jobs done. Flip both flags together when O2 ships.

Update (task 918, split): O2 only partly landed (core adoption/reconcile, Kotlin resumable push, core-backed Apple profiles; see 0935). The flags stay OFF. Remaining work moved to follow-up tasks: kit pull, Apple byte transfer, a production planner caller (or its deletion), and real-device verification, after which the owner removes the O1 flag.

Update (task 918, s942): the transfer code is complete but both flags stay OFF (gates restored) until real-device push/pull verification in task #982, which also removes the flags. Nothing claims sync works end to end before then.
