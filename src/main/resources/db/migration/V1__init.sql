CREATE TABLE jobs (
  id              UUID PRIMARY KEY,
  name            VARCHAR(100) NOT NULL,
  runtime         VARCHAR(20)  NOT NULL,
  timeout_seconds INT          NOT NULL,
  script_key      TEXT         NOT NULL,
  status          VARCHAR(30)  NOT NULL,
  instance_state  VARCHAR(30)  NOT NULL DEFAULT 'NONE',
  instance_id     VARCHAR(64),
  ssm_command_id  VARCHAR(64),
  attempt_count   INT          NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  exit_code       INT,
  failure_reason  VARCHAR(50),
  failure_detail  TEXT,
  result_prefix   TEXT,
  heartbeat_at    TIMESTAMPTZ,
  deadline_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  started_at      TIMESTAMPTZ,
  finished_at     TIMESTAMPTZ
);

CREATE INDEX idx_jobs_status ON jobs (status);
CREATE INDEX idx_jobs_dirty_instances ON jobs (instance_state)
  WHERE instance_state NOT IN ('NONE', 'TERMINATED');

CREATE TABLE job_events (
  id           BIGSERIAL PRIMARY KEY,
  job_id       UUID        NOT NULL REFERENCES jobs(id),
  from_status  VARCHAR(30),
  to_status    VARCHAR(30),
  instance_state VARCHAR(30),
  detail       TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_job_events_job ON job_events (job_id, created_at);
