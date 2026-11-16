#!/usr/bin/env bash
# M1/05: prototype of bootstrap.sh — upload script to S3, run it inside the
# instance via SSM, upload stdout/stderr/exit_code back to S3, read them here.
set -euo pipefail
. "$(dirname "$0")/_env.sh"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

PREFIX="jobs/m1-05"
printf 'print("script ran on an EC2 instance")\n' > "$TMP/m1-05-script.py"

aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null || aws s3 mb "s3://$BUCKET"
aws s3 cp "$TMP/m1-05-script.py" "s3://$BUCKET/$PREFIX/script.py"

# Default to the AL2023 AMI: python3 is preinstalled there (AL2 lacks it).
AMI="${CLOUDRUN_AMI:-$(aws ec2 describe-images --query "Images[?contains(Name, 'al2023')].ImageId" --output text | awk '{print $1}' | head -1)}"
echo "using AMI: $AMI"
IID=$(aws ec2 run-instances --image-id "$AMI" --instance-type t3.micro \
  --client-token "m1-05-$RANDOM" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=cloudrun:managed,Value=true}]' \
  --query 'Instances[0].InstanceId' --output text)
trap 'aws ec2 terminate-instances --instance-ids "$IID" >/dev/null 2>&1 || true' EXIT
aws ec2 wait instance-running --instance-ids "$IID"

# EP is the endpoint that worked in 04 (adjust once 04 records the answer).
# Bootstrap prefers the aws CLI inside the instance; falls back to unsigned
# path-style S3 via curl (Floci has S3 auth enforcement off by default).
EP="${FLOCI_INSTANCE_ENDPOINT:-http://host.docker.internal:4566}"
cat > "$TMP/bootstrap.sh" <<EOF
set -x
# runtime: python3 is not in the Floci instance image; install best-effort
if ! command -v python3 >/dev/null 2>&1; then
  (yum install -y -q python3) \
    || (apt-get update -qq && apt-get install -y -qq python3) \
    || (apk add --no-cache python3) \
    || (dnf install -y -q python3) || true
fi
command -v python3 >/dev/null 2>&1 && echo "python3: $(python3 -V)" || echo "python3: STILL MISSING"
if command -v aws >/dev/null 2>&1; then
  aws --endpoint-url $EP s3 cp s3://$BUCKET/$PREFIX/script.py /tmp/script.py
else
  curl -sf --max-time 10 "$EP/$BUCKET/$PREFIX/script.py" -o /tmp/script.py
fi
timeout 30 python3 /tmp/script.py > /tmp/stdout.txt 2> /tmp/stderr.txt; echo \$? > /tmp/exit_code.txt
if command -v aws >/dev/null 2>&1; then
  aws --endpoint-url $EP s3 cp /tmp/stdout.txt s3://$BUCKET/$PREFIX/stdout.txt
  aws --endpoint-url $EP s3 cp /tmp/stderr.txt s3://$BUCKET/$PREFIX/stderr.txt
  aws --endpoint-url $EP s3 cp /tmp/exit_code.txt s3://$BUCKET/$PREFIX/exit_code.txt
else
  curl -sf -X PUT --data-binary @/tmp/stdout.txt "$EP/$BUCKET/$PREFIX/stdout.txt"
  curl -sf -X PUT --data-binary @/tmp/stderr.txt "$EP/$BUCKET/$PREFIX/stderr.txt"
  curl -sf -X PUT --data-binary @/tmp/exit_code.txt "$EP/$BUCKET/$PREFIX/exit_code.txt"
fi
EOF

json_commands "$TMP/params.json" "$(cat "$TMP/bootstrap.sh")"
CID=$(aws ssm send-command --document-name AWS-RunShellScript \
  --instance-ids "$IID" --parameters "$(winfile "$TMP/params.json")" \
  --query Command.CommandId --output text)

for _ in $(seq 1 150); do
  STATE=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
    --query Status --output text 2>/dev/null || echo Pending)
  case "$STATE" in Success|Failed|Cancelled|TimedOut) break;; esac
  sleep 2
done
echo "bootstrap command state: $STATE"
if [ "$STATE" != "Success" ]; then
  aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
    --query '{stderr:StandardErrorContent}' --output json
fi

echo "--- stdout (read back from S3) ---"
aws s3 cp "s3://$BUCKET/$PREFIX/stdout.txt" - || fail "stdout.txt not in S3"
echo "--- exit_code ---"
aws s3 cp "s3://$BUCKET/$PREFIX/exit_code.txt" - || fail "exit_code.txt not in S3"
pass "full script flow if both reads above succeeded"

echo "finding: output method A (instance → S3) works? → docs/floci-findings.md; else try B (SSM OutputS3BucketName)"
