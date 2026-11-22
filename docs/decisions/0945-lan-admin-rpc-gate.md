# 0945 — LAN admin-RPC gate leaves UpdateConfig open

Denied on the LAN listener (without `--remote-admin`): OpenCatalog, CloseCatalog,
AddLibraryLocation, RemoveLibraryLocation. `UpdateConfig` stays allowed because the
iOS remote Settings screen calls it (auto-tag, proxy quality); blocking it would
break a shipped mobile feature. `DeleteVideo` is unchanged here (mobile delete flow).
The gate is a tower layer in `core/src/auth.rs` because tonic interceptors cannot see the method path.
