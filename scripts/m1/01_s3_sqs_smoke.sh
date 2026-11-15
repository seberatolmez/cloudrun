#!/usr/bin/env bash
# M1/01: S3 bucket, SQS queue + DLQ with RedrivePolicy, send/receive/delete.
set -euo pipefail
. "$(dirname "$0")/_env.sh"

aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null || aws s3 mb "s3://$BUCKET"

# Low visibility timeout so the redrive probe below loops fast.
MAIN_URL=$(aws sqs create-queue --queue-name "$QUEUE" \
  --attributes VisibilityTimeout=0 --query QueueUrl --output text)
DLQ_URL=$(aws sqs create-queue --queue-name "$DLQ" --query QueueUrl --output text)
DLQ_ARN=$(aws sqs get-queue-attributes --queue-url "$DLQ_URL" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

aws sqs set-queue-attributes --queue-url "$MAIN_URL" \
  --attributes RedrivePolicy="{\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":3}"
pass "queue + DLQ with RedrivePolicy created"

aws sqs send-message --queue-url "$MAIN_URL" --message-body '{"jobId":"m1-01"}'
BODY=$(aws sqs receive-message --queue-url "$MAIN_URL" \
  --query 'Messages[0].Body' --output text)
[ "$BODY" = '{"jobId":"m1-01"}' ] && pass "send/receive round-trip" \
  || fail "message body mismatch: $BODY"

RECEIPT=$(aws sqs receive-message --queue-url "$MAIN_URL" --query 'Messages[0].ReceiptHandle' --output text)
aws sqs delete-message --queue-url "$MAIN_URL" --receipt-handle "$RECEIPT"
LEFT=$(aws sqs get-queue-attributes --queue-url "$MAIN_URL" \
  --attribute-names ApproximateNumberOfMessages --query 'Attributes.ApproximateNumberOfMessages' --output text)
[ "$LEFT" = "0" ] && pass "delete removes the message" || fail "queue not empty: $LEFT"

# Redrive probe: receive 3x without deleting → message must land in the DLQ.
aws sqs send-message --queue-url "$MAIN_URL" --message-body '{"jobId":"poison"}'
for _ in 1 2 3 4; do
  aws sqs receive-message --queue-url "$MAIN_URL" >/dev/null 2>&1 || true
done
sleep 2
DLQ_DEPTH=$(aws sqs get-queue-attributes --queue-url "$DLQ_URL" \
  --attribute-names ApproximateNumberOfMessages --query 'Attributes.ApproximateNumberOfMessages' --output text)
[ "$DLQ_DEPTH" = "1" ] && pass "maxReceiveCount redrive works" \
  || fail "DLQ depth is $DLQ_DEPTH, expected 1"

echo "finding: S3/SQS/RedrivePolicy support → record in docs/floci-findings.md"
