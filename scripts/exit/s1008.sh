#!/bin/bash
# Fails if a CatalogSyncFeature flag is true while the 1001 results table has no data rows.
cd "$(dirname "$0")/../.." || exit 1
on=0
grep -Eq 'isEnabled *= *true' kit/Sources/ReelVaultKit/Sync/CatalogSyncFeature.swift && on=1
grep -Eq 'ENABLED *= *true' shared/src/main/kotlin/com/reelvault/sync/CatalogSyncFeature.kt && on=1
rows=$(grep -E '^\|' docs/decisions/1001-catalog-sync-device-verification.md | grep -vE '^\|[- |]*\|$' | grep -v '^| Client' | wc -l)
if [ "$on" = 1 ] && [ "$rows" -eq 0 ]; then
  echo "flag is true but 1001 results table is empty"; exit 1
fi
exit 0
