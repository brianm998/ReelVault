.PHONY: build run clean test help xcode

help:
	@echo "VideoRoom macOS Client - Build Commands"
	@echo "========================================"
	@echo "make build      - Build the app using Swift Package Manager"
	@echo "make run        - Build and run the app"
	@echo "make clean      - Clean build artifacts"
	@echo "make test       - Run tests"
	@echo "make xcode      - Open in Xcode (requires xcodeproj)"
	@echo "make archive    - Create an archive for distribution"

build:
	swift build -c release

run: build
	./.build/release/VideoRoom

clean:
	swift package clean
	rm -rf build
	rm -rf .build
	rm -rf DerivedData

test:
	swift test

xcode:
	open VideoRoom.xcodeproj

archive:
	swift build -c release --product VideoRoom

.DEFAULT_GOAL := help
