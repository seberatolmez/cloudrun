# Floci Findings (M1)

What works in [Floci](https://github.com/floci-io/floci) (`floci/floci:latest`,
port 4566) and what doesn't, verified by the `scripts/m1/` scenarios.
Fill each cell while running the scenarios; this drives the go/no-go decision
for M3/M4 and the output-method choice (plan §7.9).

Last checked: YYYY-MM-DD · image: `floci/floci:latest`

## Capability matrix

| # | Capability | Scenario | Status | Notes |
|---|---|---|---|---|
| 1 | S3 create bucket / put / get | 01 | TBD | |
| 2 | SQS create queue + DLQ | 01 | TBD | |
| 3 | SQS RedrivePolicy (maxReceiveCount=3) | 01 | TBD | |
| 4 | EC2 run-instances with tags | 02 | TBD | |
| 5 | EC2 `ClientToken` idempotency | 02 | TBD | falls back to tag-based sweeper if unsupported |
| 6 | `aws ec2 wait` subcommands | 02 | TBD | |
| 7 | UserData execution (timing, marker file) | 02 | TBD | |
| 8 | `get-console-output` | 02 | TBD | |
| 9 | SSM Run Command success (stdout via get-command-invocation) | 03 | TBD | |
| 10 | SSM failing command → `Failed` + stderr | 03 | TBD | |
| 11 | SSM `cancel-command` → `Cancelled` | 03 | TBD | |
| 12 | SSM `TimedOut` status (long + executionTimeout) | 03 | TBD | |
| 13 | SSM agent `Online` status (describe-instance-information) | 03 | TBD | |
| 14 | IMDS credentials inside instance | 04 | TBD | |
| 15 | Instance container → Floci network route (which endpoint URL) | 04 | TBD | |
| 16 | `aws` CLI available inside instance image | 04 | TBD | |
| 17 | `python3` available inside instance image | 05 | TBD | |
| 18 | Output method A: instance uploads results to S3 | 05 | TBD | fallback: B (SSM OutputS3BucketName) |

## Go/no-go decision

**SSM Run Command verdict:** TBD

If SSM Run Command does not actually execute commands and return output in
Floci, choose a fallback per plan §11/M1:
(a) LocalStack for these steps, (b) real AWS for SSM steps only,
(c) UserData-driven self-running worker instead of SSM.
