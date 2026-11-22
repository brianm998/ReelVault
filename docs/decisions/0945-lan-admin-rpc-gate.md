# 0945 — LAN admin-RPC gate leaves UpdateConfig open

Denied on the LAN listener (without `--remote-admin`): OpenCatalog, CloseCatalog,
AddLibraryLocation, RemoveLibraryLocation. `UpdateConfig` stays allowed because the
iOS remote Settings screen calls it (auto-tag, proxy quality); blocking it would
break a shipped mobile feature. `DeleteVideo` is unchanged here (mobile delete flow).
The gate is a tower layer in `core/src/auth.rs` because tonic interceptors cannot see the method path.

## Known residual exposure (owner decision pending)

The loopback listener is still unauthenticated: any local process can call
`DeleteVideo` (with `delete_file`) and `OpenCatalog`, now limited to what the
unprivileged `_reelvault` account can reach rather than root. `DeleteVideo` over
the LAN is also still allowed. Whether to gate `delete_file` for LAN callers is
left to the owner.

## Operational change

The daemon no longer reads library roots as root. User-chosen library roots and
`--import-dir` must be readable (import dir writable) by `_reelvault` /
`NT SERVICE\ReelVault`; the install scripts print this requirement.
