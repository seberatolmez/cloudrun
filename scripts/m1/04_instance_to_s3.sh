#!/usr/bin/env bash
# M1/04: reach S3 from inside an EC2 instance — IMDS credentials + aws s3 cp.
# Also probes which endpoint URL the instance container can use to reach Floci.
set -euo pipefail
. "$(dirname "$0")/_env.sh"

aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null || aws s3 mb "s3://$BUCKET"
printf 'cloudrun-m1-04\n' > /tmp/m1-04.txt
aws s3 cp /tmp/m1-04.txt "s3://$BUCKET/m1-04/test.txt"

AMI="${CLOUDRUN_AMI:-$(aws ec2 describe-images --query 'Images[0].ImageId' --output text)}"
IID=$(aws ec2 run-instances --image-id "$AMI" --instance-type t3.micro \
  --client-token "m1-04-$RANDOM" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=cloudrun:managed,Value=true}]' \
  --query 'Instances[0].InstanceId' --output text)
trap 'aws ec2 terminate-instances --instance-ids "$IID" >/dev/null 2>&1 || true' EXIT
aws ec2 wait instance-running --instance-ids "$IID"

# Probe script run inside the instance: IMDS creds, then S3 download via
# each candidate endpoint. Results are printed to the SSM command output.
read -r -d '' PROBE <<'EOF'
echo "--- imds creds ---"
curl -s http://169.254.169.254/latest/api/token -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' | head -c 40; echo
curl -s -H "X-aws-ec2-metadata-token: $(curl -s -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')" http://169.254.169.254/latest/meta-data/iam/security-credentials/ || echo "imds creds: FAILED"
echo "--- endpoint probes ---"
for EP in http://host.docker.internal:4566 http://floci:4566 http://172.17.0.1:4566; do
  echo "trying $EP"
  aws --endpoint-url "$EP" s3 cp s3://cloudrun-artifacts/m1-04/test.txt /tmp/test.txt \
    && { echo "OK via $EP"; cat /tmp/test.txt; break; } \
    || echo "no route via $EP"
done
EOF

CID=$(aws ssm send-command --document-name AWS-RunShellScript \
  --instance-ids "$IID" --parameters "commands=[$PROBE]" \
  --query Command.CommandId --output text)

for _ in $(seq 1 90); do
  STATE=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
    --query Status --output text 2>/dev/null || echo Pending)
  case "$STATE" in Success|Failed|Cancelled|TimedOut) break;; esac
  sleep 2
done
aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
  --query '{status:Status,output:StandardOutputContent}' --output json

echo "finding: IMDS support, reachable endpoint, instance image tooling (curl/aws) → docs/floci-findings.md"
