.PHONY: build release run run-release clean test help xcode archive

help:
	@echo "ReelVault macOS Client - Build Commands"
	@echo "========================================"
	@echo "make build       - Fast debug build (incremental; use during development)"
	@echo "make run         - Build (debug) and run the app"
	@echo "make release     - Optimized release build (slow; for distribution)"
	@echo "make run-release - Build (release) and run the app"
	@echo "make clean       - Clean build artifacts"
	@echo "make test        - Run tests"
	@echo "make xcode       - Open in Xcode (requires xcodeproj)"
	@echo "make archive     - Create a release build for distribution"

# Debug is the default for development: incremental compilation, no -O.
# A one-line change rebuilds in seconds instead of recompiling the whole
# module (incl. ~19k lines of generated gRPC/protobuf) under optimization.
build:
	swift build

run: build
	./.build/debug/ReelVault

# Optimized whole-module build — minutes, not seconds. For distribution
# or when profiling runtime performance.
release:
	swift build -c release

run-release: release
	./.build/release/ReelVault

clean:
	swift package clean
	rm -rf build
	rm -rf .build
	rm -rf DerivedData

test:
	swift test

xcode:
	open ReelVault.xcodeproj

archive:
	swift build -c release --product ReelVault

.DEFAULT_GOAL := help
