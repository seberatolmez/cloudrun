#!/usr/bin/env bash
# M1/04: reach S3 from inside an EC2 instance — IMDS credentials + aws s3 cp.
# Also probes which endpoint URL the instance container can use to reach Floci.
set -euo pipefail
. "$(dirname "$0")/_env.sh"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null || aws s3 mb "s3://$BUCKET"
printf 'cloudrun-m1-04\n' > "$TMP/m1-04.txt"
aws s3 cp "$TMP/m1-04.txt" "s3://$BUCKET/m1-04/test.txt"

AMI="${CLOUDRUN_AMI:-$(aws ec2 describe-images --query 'Images[0].ImageId' --output text)}"
IID=$(aws ec2 run-instances --image-id "$AMI" --instance-type t3.micro \
  --client-token "m1-04-$RANDOM" \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=cloudrun:managed,Value=true}]' \
  --query 'Instances[0].InstanceId' --output text)
trap 'aws ec2 terminate-instances --instance-ids "$IID" >/dev/null 2>&1 || true' EXIT
aws ec2 wait instance-running --instance-ids "$IID"

# Probe script run inside the instance: which tools exist, raw TCP
# reachability to Floci, then unsigned S3 download via curl (path-style).
cat > "$TMP/probe.sh" <<'EOF'
echo "--- tools ---"
for t in bash curl python3 aws; do
  command -v $t >/dev/null 2>&1 && echo "$t: $(command -v $t)" || echo "$t: MISSING"
done
echo "--- tcp reachability to :4566 ---"
for EP in host.docker.internal floci 172.17.0.1; do
  (echo > /dev/tcp/$EP/4566) 2>/dev/null && echo "$EP:4566 reachable" || echo "$EP:4566 no route"
done
echo "--- unsigned S3 via curl (path-style) ---"
if command -v curl >/dev/null 2>&1; then
  for EP in host.docker.internal floci 172.17.0.1; do
    BODY=$(curl -s --max-time 5 "http://$EP:4566/cloudrun-artifacts/m1-04/test.txt")
    [ "$BODY" = "cloudrun-m1-04" ] && { echo "OK via $EP"; break; } || echo "no via $EP"
  done
fi
echo "--- imds ---"
curl -s --max-time 5 http://169.254.169.254/latest/meta-data/ \
  || echo "imds: FAILED"
echo "--- os / package manager ---"
head -3 /etc/os-release 2>/dev/null || echo "no os-release"
for pm in apt-get apk dnf yum; do command -v $pm || true; done
EOF

json_commands "$TMP/params.json" "$(cat "$TMP/probe.sh")"
CID=$(aws ssm send-command --document-name AWS-RunShellScript \
  --instance-ids "$IID" --parameters "$(winfile "$TMP/params.json")" \
  --query Command.CommandId --output text)

for _ in $(seq 1 90); do
  STATE=$(aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
    --query Status --output text 2>/dev/null || echo Pending)
  case "$STATE" in Success|Failed|Cancelled|TimedOut) break;; esac
  sleep 2
done
aws ssm get-command-invocation --command-id "$CID" --instance-id "$IID" \
  --query '{status:Status,output:StandardOutputContent,stderr:StandardErrorContent}' --output json
echo "finding: IMDS support, reachable endpoint, instance image tooling (curl/aws) → docs/floci-findings.md"
