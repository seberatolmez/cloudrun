#!/usr/bin/env bash
# M1/02: EC2 lifecycle — run-instances (UserData, tags, ClientToken), wait,
# ClientToken idempotency probe, terminate, wait.
set -euo pipefail
. "$(dirname "$0")/_env.sh"

TMP=$(mktemp -d)
printf '#!/bin/bash\necho cloudrun-userdata-ok > /tmp/userdata-marker\n' > "$TMP/userdata.sh"
trap 'rm -rf "$TMP"' EXIT

# Discover an AMI Floci offers; override with CLOUDRUN_AMI if needed.
AMI="${CLOUDRUN_AMI:-$(aws ec2 describe-images --query 'Images[0].ImageId' --output text)}"
echo "using AMI: $AMI (override with CLOUDRUN_AMI)"

TOKEN="m1-02-$RANDOM"
IID=$(aws ec2 run-instances --image-id "$AMI" --instance-type t3.micro \
  --client-token "$TOKEN" \
  --user-data "file://$TMP/userdata.sh" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=cloudrun:managed,Value=true},{Key=job_id,Value=m1-02}]' \
  --query 'Instances[0].InstanceId' --output text)
pass "run-instances returned $IID"

aws ec2 wait instance-running --instance-ids "$IID" \
  && pass "wait instance-running" || fail "wait instance-running"

# Idempotency probe: same ClientToken must not create a second instance.
aws ec2 run-instances --image-id "$AMI" --instance-type t3.micro \
  --client-token "$TOKEN" --user-data "file://$TMP/userdata.sh" \
  >/dev/null
COUNT=$(aws ec2 describe-instances \
  --filters "Name=client-token,Values=$TOKEN" \
  --query 'Reservations[].Instances[]' --output json | grep -c InstanceId || true)
[ "$COUNT" = "1" ] && pass "ClientToken is idempotent" \
  || fail "ClientToken created $COUNT instances (expected 1)"

TAGS=$(aws ec2 describe-tags --filters "Name=resource-id,Values=$IID" --output json)
echo "tags: $TAGS"

# UserData effect is hard to observe from the host; try console output, record result.
aws ec2 get-console-output --instance-id "$IID" 2>&1 | head -5 || \
  fail "get-console-output unsupported (expected in Floci?)"

aws ec2 terminate-instances --instance-ids "$IID" >/dev/null
aws ec2 wait instance-terminated --instance-ids "$IID" \
  && pass "terminate + wait instance-terminated" || fail "wait instance-terminated"

echo "finding: UserData timing, wait support, ClientToken → docs/floci-findings.md"
