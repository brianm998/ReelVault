#!/bin/bash
set -e
cd "$(dirname "$0")/../../core" && cargo test --lib total_count_is optional_joins facet
