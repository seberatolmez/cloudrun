#!/usr/bin/env bash
# M1/03: SSM Run Command — success, failure (exit 1), and cancel of a long command.
set -euo pipefail
. "$(dirname "$0")/_env.sh"

AMI="${CLOUDRUN_AMI:-$(aws ec2 describe-images --query 'Images[0].ImageId' --output text)}"
IID=$(aws ec2 run-instances --image-id "$AMI" --instance-type t3.micro \
  --client-token "m1-03-$RANDOM" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=cloudrun:managed,Value=true}]' \
  --query 'Instances[0].InstanceId' --output text)
trap 'aws ec2 terminate-instances --instance-ids "$IID" >/dev/null 2>&1 || true' EXIT
aws ec2 wait instance-running --instance-ids "$IID"
echo "instance: $IID"

wait_command() { # $1 = command id → echoes terminal state
  for _ in $(seq 1 60); do
    STATE=$(aws ssm get-command-invocation --command-id "$1" --instance-id "$IID" \
      --query Status --output text 2>/dev/null || echo Pending)
    case "$STATE" in
      Success|Failed|Cancelled|TimedOut) echo "$STATE"; return 0;;
    esac
    sleep 2
  done
  echo PENDING_TIMEOUT
}

send() { # $1 = shell command → echoes command id
  aws ssm send-command --document-name AWS-RunShellScript \
    --instance-ids "$IID" --parameters "commands=[$1]" \
    --query Command.CommandId --output text
}

CID=$(send 'echo hello')
STATE=$(wait_command "$CID")
OUT=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
  --query StandardOutputContent --output text)
[ "$STATE" = "Success" ] && [ "$OUT" = "hello" ] && pass "run command: $STATE, stdout=$OUT" \
  || fail "run command: state=$STATE stdout=$OUT"

CID=$(send 'exit 1')
STATE=$(wait_command "$CID")
ERR=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
  --query StandardErrorContent --output text)
[ "$STATE" = "Failed" ] && pass "failing command: $STATE, stderr=$ERR" \
  || fail "failing command: state=$STATE stderr=$ERR"

CID=$(send 'sleep 60')
wait_command "$CID" >/dev/null &   # let it start
sleep 5
aws ssm cancel-command --command-id "$CID" 2>/dev/null \
  && pass "cancel-command accepted" || fail "cancel-command rejected"
STATE=$(wait_command "$CID")
echo "cancelled command state: $STATE (expected Cancelled)"

echo "finding: SSM status mapping (Success/Failed/Cancelled/TimedOut), agent Online flow → docs/floci-findings.md"
