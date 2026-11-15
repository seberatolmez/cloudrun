#!/usr/bin/env bash
# Shared environment for M1 spike scripts. Source this, don't execute it.
set -euo pipefail
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
export AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost:4566}"
export BUCKET="${BUCKET:-cloudrun-artifacts}"
export QUEUE="${QUEUE:-cloudrun-jobs}"
export DLQ="${DLQ:-cloudrun-jobs-dlq}"

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; }
