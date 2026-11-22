# 0963: Remove-location uses an exact byte-range prefix

Status: accepted

`remove_library_location` deleted rows with `path LIKE ? || '%'`. `_` in the
root is a wildcard and LIKE is ASCII case-insensitive, so sibling folders such
as `ProjectX007` or `project_007` were deleted too.

Decision: destructive deletes use `path >= lo AND path < hi` from
`path_prefix_range`, as #906 did for read paths. The unescaped LIKEs in
search.rs and cli.rs are non-destructive and out of scope.
