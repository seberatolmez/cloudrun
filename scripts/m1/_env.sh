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

# Write SSM parameters JSON {"commands":[...]} for a file:// argument.
# Needed because embedded double quotes in CLI args are mangled between
# MSYS bash and the native aws.exe on Windows.
json_commands() { # $1 = output file, rest = command strings
  python - "$@" <<'PY'
import json, sys
out, cmds = sys.argv[1], sys.argv[2:]
with open(out, "w") as f:
    json.dump({"commands": cmds}, f)
PY
}

# Windows-safe path for an aws file:// argument.
winfile() { echo "file://$(cygpath -m "$1")"; }
