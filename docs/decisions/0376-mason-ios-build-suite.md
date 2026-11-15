# 0376: MASON iOS build suite

- The XCFramework (gitignored, multi-arch Rust cross-compile) is cached under
  `~/.cache/reelvault-xcframework/<core tree hash>-<rustc -V>-debug` and written atomically (temp dir + mv).
- Build only: `xcodebuild build` for `generic/platform=iOS Simulator` with `CODE_SIGNING_ALLOWED=NO`.
  That destination needs no booted simulator, so nothing appears on screen. No test action, no signing material.
