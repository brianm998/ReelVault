#!/bin/bash
set -e
cd "$(dirname "$0")/../../core"
cargo test --lib sync::
