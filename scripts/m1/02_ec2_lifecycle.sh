#!/usr/bin/env bash
# M1/02: EC2 lifecycle — run-instances (UserData, tags, ClientToken), wait,
# ClientToken idempotency probe, terminate, wait.
set -euo pipefail
. "$(dirname "$0")/_env.sh"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

printf '#!/bin/bash\necho cloudrun-userdata-ok > /tmp/userdata-marker\n' > "$TMP/userdata.sh"

# Discover an AMI Floci offers; override with CLOUDRUN_AMI if needed.
AMI="${CLOUDRUN_AMI:-$(aws ec2 describe-images --query 'Images[0].ImageId' --output text)}"
echo "using AMI: $AMI (override with CLOUDRUN_AMI)"

TOKEN="m1-02-$RANDOM"
IID=$(aws ec2 run-instances --image-id "$AMI" --instance-type t3.micro \
  --client-token "$TOKEN" \
  --user-data "$(winfile "$TMP/userdata.sh")" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=cloudrun:managed,Value=true},{Key=job_id,Value=m1-02}]' \
  --query 'Instances[0].InstanceId' --output text)
pass "run-instances returned $IID"

aws ec2 wait instance-running --instance-ids "$IID" \
  && pass "wait instance-running" || fail "wait instance-running"

# UserData effect: SSM agent may come Online before UserData finishes; retry briefly.
json_commands "$TMP/params.json" 'cat /tmp/userdata-marker 2>/dev/null || echo no-marker'
CID=$(aws ssm send-command --document-name AWS-RunShellScript \
  --instance-ids "$IID" --parameters "$(winfile "$TMP/params.json")" \
  --query Command.CommandId --output text)
for _ in $(seq 1 15); do
  sleep 4
  STATE=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
    --query Status --output text 2>/dev/null || echo Pending)
  [ "$STATE" = "Success" ] && break
done
MARKER=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
  --query StandardOutputContent --output text)
echo "$MARKER" | grep -q cloudrun-userdata-ok \
  && pass "UserData executed (marker present)" \
  || fail "UserData marker not found: state=$STATE output=$MARKER"
if ! echo "$MARKER" | grep -q cloudrun-userdata-ok; then
  # Diagnostic: did UserData leave any trace?
  json_commands "$TMP/params.json" 'ls /tmp /var/tmp /etc 2>/dev/null | head -40; ls /var/lib/cloud 2>/dev/null'
  CID2=$(aws ssm send-command --document-name AWS-RunShellScript \
    --instance-ids "$IID" --parameters "$(winfile "$TMP/params.json")" \
    --query Command.CommandId --output text)
  for _ in $(seq 1 15); do
    sleep 3
    S=$(aws ssm get-command-invocation --command-id "$CID2" --instance-id "$IID" \
      --query Status --output text 2>/dev/null || echo Pending)
    [ "$S" = "Success" ] && break
  done
  aws ssm get-command-invocation --command-id "$CID2" --instance-id "$IID" \
    --query StandardOutputContent --output text | head -40
fi

# Idempotency probe: identical request with same ClientToken must return the
# same instance, not a new one.
IID2=$(aws ec2 run-instances --image-id "$AMI" --instance-type t3.micro \
  --client-token "$TOKEN" \
  --user-data "$(winfile "$TMP/userdata.sh")" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=cloudrun:managed,Value=true},{Key=job_id,Value=m1-02}]' \
  --query 'Instances[0].InstanceId' --output text)
if [ "$IID2" = "$IID" ]; then
  pass "ClientToken is idempotent"
else
  fail "ClientToken created a second instance: $IID2 (terminating it)"
  aws ec2 terminate-instances --instance-ids "$IID2" >/dev/null || true
fi

aws ec2 describe-tags --filters "Name=resource-id,Values=$IID" --output json

# UserData effect is hard to observe from the host; try console output, record result.
aws ec2 get-console-output --instance-id "$IID" 2>&1 | head -5 || \
  fail "get-console-output unsupported (expected in Floci?)"

aws ec2 terminate-instances --instance-ids "$IID" >/dev/null
aws ec2 wait instance-terminated --instance-ids "$IID" \
  && pass "terminate + wait instance-terminated" || fail "wait instance-terminated"

echo "finding: UserData timing, wait support, ClientToken → docs/floci-findings.md"
