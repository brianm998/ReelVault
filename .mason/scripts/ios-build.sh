#!/usr/bin/env bash
# Build the iOS app for the simulator, unsigned (same xcodebuild as ci.yml). Build only: no tests.
# The embedded-core XCFramework is cached keyed on the core/ tree hash + rustc version.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

for t in xcodegen xcodebuild cargo rustc protoc; do
  command -v "$t" >/dev/null || { echo "error: required tool '$t' not found on PATH"; exit 1; }
done

KEY="$(git rev-parse HEAD:core)-$(rustc -V | tr ' ' _)-debug"
CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/reelvault-xcframework"
ENTRY="$CACHE_ROOT/$KEY"
mkdir -p "$ROOT/ios/Frameworks" "$CACHE_ROOT"

if [ -d "$ENTRY/ReelVaultCore.xcframework" ]; then
  echo "xcframework cache hit: $KEY"
else
  echo "xcframework cache miss: $KEY; building"
  (cd ios && make xcframework)
  TMP=$(mktemp -d "$CACHE_ROOT/.tmp.XXXXXX")
  cp -R ios/Frameworks/ReelVaultCore.xcframework "$TMP/"
  if ! mv "$TMP" "$ENTRY" 2>/dev/null; then rm -rf "$TMP"; fi  # lost a race: entry exists
fi
rm -rf ios/Frameworks/ReelVaultCore.xcframework
cp -R "$ENTRY/ReelVaultCore.xcframework" ios/Frameworks/

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
