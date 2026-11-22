#!/bin/bash
set -e
cd "$(dirname "$0")/../../core" && cargo test --lib admin_gate reserve_tests
