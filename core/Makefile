.PHONY: help build release run run-release dev cli test check clippy fmt fmt-check doc clean install

# Extra args passed through to `run`, `run-release`, `dev`, and `cli`.
# Example: `make run ARGS="--port 0 --no-catalog"`
#          `make cli ARGS="scan /Volumes/footage -r"`
ARGS ?=

help:
	@echo "ReelVault Core (Rust daemon) - Build Commands"
	@echo "============================================="
	@echo "make build       - cargo build (debug)"
	@echo "make release     - cargo build --release"
	@echo "make run         - Run the daemon (debug). Pass flags via ARGS=..."
	@echo "make run-release - Run the daemon (release). Pass flags via ARGS=..."
	@echo "make dev         - Run the daemon with RUST_LOG=debug"
	@echo "make cli         - Run the reelvault-cli binary. Pass args via ARGS=..."
	@echo "make test        - cargo test"
	@echo "make check       - cargo check (fast type-check, no codegen)"
	@echo "make clippy      - cargo clippy -- -D warnings"
	@echo "make fmt         - cargo fmt"
	@echo "make fmt-check   - cargo fmt --check (CI-friendly)"
	@echo "make doc         - cargo doc --no-deps --open"
	@echo "make clean       - cargo clean"
	@echo "make install     - cargo install --path . (installs both binaries to ~/.cargo/bin)"
	@echo ""
	@echo "Examples:"
	@echo "  make run ARGS=\"--port 0 --no-catalog\""
	@echo "  make cli ARGS=\"scan /Volumes/footage -r\""

build:
	cargo build

release:
	cargo build --release

run:
	cargo run --bin reelvault-core -- $(ARGS)

run-release:
	cargo run --release --bin reelvault-core -- $(ARGS)

dev:
	RUST_LOG=debug cargo run --bin reelvault-core -- $(ARGS)

cli:
	cargo run --bin reelvault-cli -- $(ARGS)

test:
	cargo test

check:
	cargo check

clippy:
	cargo clippy --all-targets -- -D warnings

fmt:
	cargo fmt

fmt-check:
	cargo fmt --check

doc:
	cargo doc --no-deps --open

clean:
	cargo clean

install:
	cargo install --path .

.DEFAULT_GOAL := help
