#!/usr/bin/env bash
# Build the iOS app for the simulator, unsigned (same xcodebuild as ci.yml). Build only: no tests.
# The embedded-core XCFramework is cached keyed on the core/ tree hash + rustc version.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

for t in xcodegen xcodebuild cargo rustc protoc; do
  command -v "$t" >/dev/null || { echo "error: required tool '$t' not found on PATH"; exit 1; }
done

export PROFILE="${PROFILE:-debug}"
KEY="$(git rev-parse HEAD:core)-$(rustc -V | tr ' ' _)-$PROFILE"
CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/reelvault-xcframework"
ENTRY="$CACHE_ROOT/$KEY"
KEEP=3
mkdir -p "$ROOT/ios/Frameworks" "$CACHE_ROOT"

# The key names the committed core/ tree; uncommitted edits would make the build differ from it.
if [ -n "$(git status --porcelain -- core)" ]; then
  echo "core/ has uncommitted changes: building without the cache"
  (cd ios && make xcframework)
  ENTRY=""
elif [ -d "$ENTRY/ReelVaultCore.xcframework" ]; then
  echo "xcframework cache hit: $KEY"
  touch "$ENTRY"
else
  echo "xcframework cache miss: $KEY; building"
  (cd ios && make xcframework)
  TMP=$(mktemp -d "$CACHE_ROOT/.tmp.XXXXXX")
  cp -R ios/Frameworks/ReelVaultCore.xcframework "$TMP/"
  if [ -d "$ENTRY" ]; then
    rm -rf "$TMP"  # lost a race: entry exists
  else
    mv "$TMP" "$ENTRY"
  fi
  # Evict all but the KEEP most recently used entries.
  # shellcheck disable=SC2012
  ls -1t "$CACHE_ROOT" | grep -v -e '^\.' -e '^SourcePackages$' | tail -n +$((KEEP + 1)) |
    while read -r old; do rm -rf "${CACHE_ROOT:?}/$old"; done
fi
if [ -n "$ENTRY" ]; then
  rm -rf ios/Frameworks/ReelVaultCore.xcframework
  cp -R "$ENTRY/ReelVaultCore.xcframework" ios/Frameworks/
fi

cd ios
xcodegen generate
xcodebuild \
  -project ReelVault.xcodeproj \
  -scheme ReelVault \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath DerivedData \
  -clonedSourcePackagesDirPath "$CACHE_ROOT/SourcePackages" \
  CODE_SIGNING_ALLOWED=NO \
  build
echo IOS-BUILD-OK
