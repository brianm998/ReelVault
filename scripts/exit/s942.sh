#!/bin/bash
set -e
cd "$(dirname "$0")/../../core"
cargo test --lib sync::
cargo test --test sync_e2e
