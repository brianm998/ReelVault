# 0916: Contain catalog sync behind a flag

Sync could not transfer anything yet showed "synced". Until O2 lands, one flag per language (kit, shared) hides all entry points and disables auto-sync. The Swift scaffold throws `SyncError.notAvailable` rather than marking jobs done. Flip both flags together when O2 ships.

Update (task 918, split): O2 only partly landed (core adoption/reconcile, Kotlin resumable push, core-backed Apple profiles; see 0935). The flags stay OFF. Remaining work moved to follow-up tasks: kit pull, Apple byte transfer, a production planner caller (or its deletion), and real-device verification, after which the owner removes the O1 flag.

Update (task 918, s942): O2 shipped and both flags were removed; the entry points and auto-sync are live.
