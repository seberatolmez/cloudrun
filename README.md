# cloudrun

Run Python scripts on ephemeral AWS EC2 instances: `POST /jobs` → a temporary
instance is launched, the script runs through SSM, stdout/stderr/exit code land
in S3, the job state lives in PostgreSQL, and the instance is always terminated
— even on failure.

## Architecture

```
Client ──POST /jobs──▶ Spring Boot API ──▶ S3 (script) + SQS (trigger)
                                │
                          PostgreSQL (state)
                                │
                        SQS poller / worker
                                │
                        EC2 + SSM Run Command
                                │
                    results → S3, instance → terminated
```

Key design points: conditional DB claim for idempotency, heartbeats + a
reconciler for worker crashes, three timeout layers, a DLQ with a drainer,
and an orphan-instance sweeper. The full plan (Turkish) is kept locally and
not committed.

## Status

| Milestone | Description | State |
|---|---|---|
| M1 | Probe Floci (local AWS emulator) with AWS CLI | in progress |
| M2 | API + PostgreSQL + SQS skeleton with fake compute | planned |
| M3 | Real EC2 + SSM adapters (AWS SDK v2) | planned |
| M4 | S3 artifacts + result API | planned |
| M5 | Reliability mechanisms | planned |
| M6 | Terraform + CI | planned |
| M7 | Real-AWS smoke test + showcase README | planned |

## Quickstart (M1 spike)

Requires Docker and the AWS CLI. Runs the Floci + PostgreSQL stack locally and
executes all spike scenarios end to end:

```sh
make up
make m1
```

Findings (what works in Floci, what doesn't) are recorded in
[`docs/floci-findings.md`](docs/floci-findings.md).
