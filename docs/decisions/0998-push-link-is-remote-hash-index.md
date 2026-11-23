# 0998: Kotlin push uses the remote content-hash index as its link

The core has no client RPC to write a `sync_links` row on a peer (`add_sync_link` is
internal to `ReconcileCatalog`). Adding one would force a proto change plus Swift stub
regeneration. `pushFile` already resolves the remote video by `LookupByContentHash`
before uploading and again after the final chunk, and applies catalog data to that id,
so a retry after a failed apply, or a later run, never uploads twice. Tests in
`PushFileTest` cover both acceptance criteria plus a lost final-chunk response.
Apple push (#980) can follow the same lookup-first order. Revisit if #982 needs
local-side links for pushed videos.
