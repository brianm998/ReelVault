package com.reelvault.sync

/**
 * Master switch for catalog sync (push/pull/mirror).
 *
 * OFF until the transfer path actually exists (audit item O1 / O2): the daemon
 * has no local plaintext media server for push to read from. While false, every
 * client hides its sync entry points and background auto-sync does not run.
 * Keep in step with `CatalogSyncFeature.isEnabled` in `kit/`.
 */
object CatalogSyncFeature {
    const val ENABLED = false
}
