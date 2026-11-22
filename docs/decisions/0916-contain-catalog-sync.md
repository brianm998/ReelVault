# 0916: Contain catalog sync behind a flag

Sync could not transfer anything yet showed "synced". Until O2 lands, one flag per language (kit, shared) hides all entry points and disables auto-sync. The Swift scaffold throws `SyncError.notAvailable` rather than marking jobs done. Flip both flags together when O2 ships.
