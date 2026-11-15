#!/usr/bin/env bash
# M1/00: start the local stack and wait until Floci answers.
set -euo pipefail
cd "$(dirname "$0")/.."
cd ..

docker compose up -d

for _ in $(seq 1 60); do
  if curl -sf http://localhost:4566/_localstack/health >/dev/null 2>&1; then
    echo "floci is ready at http://localhost:4566"
    exit 0
  fi
  sleep 2
done

echo "floci did not become healthy in 120s" >&2
docker compose logs --tail 50 floci >&2
exit 1
