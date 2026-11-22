#!/bin/bash
set -e
cd "$(dirname "$0")/../../core" && cargo test --lib total_count_is && cargo test --lib optional_joins && cargo test --lib facet
