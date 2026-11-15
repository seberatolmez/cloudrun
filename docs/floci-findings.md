# Floci Findings (M1)

What works in [Floci](https://github.com/floci-io/floci) (`floci/floci:latest`,
port 4566) and what doesn't, verified by the `scripts/m1/` scenarios.
This drives the go/no-go decision for M3/M4 and the output-method choice
(plan §7.9).

Last checked: 2026-11-15 · image: `floci/floci:latest` · Floci 2.5.0 · host: Windows 11 + Docker Desktop

## Capability matrix

| # | Capability | Scenario | Status | Notes |
|---|---|---|---|---|
| 1 | S3 create bucket / put / get | 01 | ✅ | |
| 2 | SQS create queue + DLQ | 01 | ✅ | |
| 3 | SQS RedrivePolicy (maxReceiveCount=3) | 01 | ✅ | poison message moved to DLQ after 3 receives |
| 4 | EC2 run-instances with tags | 02 | ✅ | tags stored and returned |
| 5 | EC2 `ClientToken` idempotency | 02 | ❌ | identical retry with same token created a **second** instance → tag-based sweeper is mandatory (plan §7.1) |
| 6 | `aws ec2 wait` subcommands | 02 | ✅ | `instance-running`, `instance-terminated` both work |
| 7 | UserData execution | 02 | ❌ | marker script never ran (both `file://` and inline user-data); console shows cloud-init finished but no script trace → fallback (c) of plan §11/M1 is not available |
| 8 | `get-console-output` | 02 | ✅ | cloud-init banner + SSH host keys returned |
| 9 | SSM Run Command success (stdout via get-command-invocation) | 03 | ✅ | `echo hello` → `Success`, stdout returned |
| 10 | SSM failing command → `Failed` | 03 | ✅ | `exit 1` → `Failed`; stderr empty (command produced none) |
| 11 | SSM `cancel-command` → `Cancelled` | 03 | ✅ | |
| 12 | SSM `executionTimeout` → `TimedOut` | 03 | ❌ | `--timeout-seconds 30` ignored, `sleep 90` still finished `Success` → orchestrator must enforce its own `deadline_at` and use `CancelCommand` |
| 13 | SSM agent `Online` (describe-instance-information) | 03 | ⚠️ | list is empty even though commands run — don't rely on `PingStatus`; use command round-trips for readiness |
| 14 | IMDS credentials inside instance | 04 | ❌ | 169.254.169.254 unreachable from the instance container |
| 15 | Instance container → Floci network route | 04 | ✅ | `http://host.docker.internal:4566` (also `floci:4566`, `172.17.0.1:4566`) |
| 16 | `aws` CLI inside instance image | 04 | ❌ | missing in both AMIs — bootstrap must use curl instead |
| 17 | `python3` inside instance image | 04/05 | ✅ | **AL2023 AMI ships python3 3.9.25** (AL2 does not, but `yum install python3` works — internet and DNS reachable) → default to AL2023 |
| 18 | Output method A: instance → S3 | 05 | ✅ | full round-trip: upload script → run via SSM → stdout/stderr/exit_code uploaded to S3 and read back; exit_code 0 |

## Instance environment (scenario 04 probe)

- AMIs offered: Amazon Linux 2 (`ami-0abcdef1234567890`) and Amazon Linux 2023 (`ami-0abcdef1234567891`)
- AL2023: `bash`, `curl`, `python3` (3.9) present; `aws` missing
- AL2: `bash`, `curl` present; `python3` missing but `yum install -y python3` succeeds (internet works, Docker DNS at 127.0.0.11)
- S3 access without SigV4 works: Floci serves unsigned path-style requests
  (`FLOCI_SERVICES_S3_ENFORCE_AUTH` defaults to `false`) → bootstrap uploads
  results with `curl -X PUT --data-binary @file http://host.docker.internal:4566/<bucket>/<key>`
- IMDS (169.254.169.254): not reachable from the instance container

## Go/no-go decision

**SSM Run Command verdict: GO.** Floci runs `AWS-RunShellScript` commands for
real, returns stdout/stderr, maps failures to `Failed`, and supports
`cancel-command`. No fallback for M3/M4 is needed.

## Consequences for the design

- §7.1 idempotency: `ClientToken` is not honored → the DB conditional claim
  stays the single protection; the tag-based sweeper must also detect and
  terminate duplicate instances from retried `RunInstances` calls.
- §7.4 timeouts: SSM `executionTimeout` is not enforced → the three timeout
  layers collapse to two: provisioning timeout + orchestrator `deadline_at`
  with `CancelCommand`.
- §7.9 bootstrap: use the **AL2023 AMI** (python3 preinstalled), download the
  script and upload stdout/stderr/exit_code via **unsigned path-style S3 over
  curl** (method A without IAM). On real AWS the same bootstrap must switch to
  SigV4 with the instance profile.
- UserData cannot bootstrap the instance (never executes) → all bootstrapping
  goes through SSM commands after the agent is reachable.
- `describe-instance-information` returns an empty list → readiness check must
  be "first SSM command succeeds", not `PingStatus == Online`.
