package dev.cloudrun.job;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Every state change goes through a conditional UPDATE ... WHERE status = ?,
 * so concurrent workers and redelivered messages cannot double-process a job.
 * Each successful change appends a row to job_events in the same transaction.
 */
@Repository
public class JobRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JobRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public void insert(Job job) {
        jdbc.sql("""
                INSERT INTO jobs (id, name, runtime, timeout_seconds, script_key, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(job.id(), job.name(), job.runtime(), job.timeoutSeconds(), job.scriptKey(), job.status().name())
                .update();
    }

    /** PENDING -> QUEUED. False if the job is no longer PENDING. */
    public boolean markQueued(UUID jobId) {
        return transition(jobId, JobStatus.PENDING, JobStatus.QUEUED);
    }

    /**
     * PENDING|QUEUED -> PROVISIONING. Bumps attempt_count and sets started_at; false when someone
     * else claimed the job. PENDING is accepted because a message only exists once it was sent,
     * and the poller can receive it before the API commits markQueued.
     */
    public boolean claim(UUID jobId) {
        return tx.execute(status -> {
            var current = jdbc.sql("SELECT status FROM jobs WHERE id = ? FOR UPDATE")
                    .param(jobId)
                    .query(String.class)
                    .optional()
                    .map(JobStatus::valueOf)
                    .orElse(null);
            if (current == null || !current.canTransitionTo(JobStatus.PROVISIONING)) {
                return false;
            }
            jdbc.sql("""
                            UPDATE jobs
                            SET status = 'PROVISIONING',
                                attempt_count = attempt_count + 1,
                                started_at = now(),
                                updated_at = now()
                            WHERE id = ?
                            """)
                    .param(jobId)
                    .update();
            recordEvent(jobId, current, JobStatus.PROVISIONING, null, "claimed");
            return true;
        });
    }

    public boolean transition(UUID jobId, JobStatus from, JobStatus to) {
        if (!from.canTransitionTo(to)) {
            return false;
        }
        return tx.execute(status -> {
            int rows = jdbc.sql("""
                            UPDATE jobs SET status = ?, updated_at = now()
                            WHERE id = ? AND status = ?
                            """)
                    .params(to.name(), jobId, from.name())
                    .update();
            if (rows == 0) {
                return false;
            }
            recordEvent(jobId, from, to, null, null);
            return true;
        });
    }

    /** Records the instance id and its RUNNING state after provisioning. */
    public boolean instanceReady(UUID jobId, String instanceId) {
        return tx.execute(status -> {
            int rows = jdbc.sql("""
                            UPDATE jobs
                            SET instance_id = ?, instance_state = 'RUNNING', updated_at = now()
                            WHERE id = ?
                            """)
                    .params(instanceId, jobId)
                    .update();
            if (rows == 0) {
                return false;
            }
            recordEvent(jobId, null, null, InstanceState.RUNNING, "instance ready: " + instanceId);
            return true;
        });
    }

    public boolean instanceTerminated(UUID jobId, InstanceState state) {
        return tx.execute(status -> {
            int rows = jdbc.sql("""
                            UPDATE jobs SET instance_state = ?, updated_at = now()
                            WHERE id = ?
                            """)
                    .params(state.name(), jobId)
                    .update();
            if (rows == 0) {
                return false;
            }
            recordEvent(jobId, null, null, state, null);
            return true;
        });
    }

    /** Final transition out of COLLECTING_RESULTS: SUCCEEDED or FAILED with exit code and result prefix. */
    public boolean finish(UUID jobId, JobStatus to, Integer exitCode, String failureReason,
                          String failureDetail, String resultPrefix) {
        return tx.execute(status -> {
            int rows = jdbc.sql("""
                            UPDATE jobs
                            SET status = ?, exit_code = ?, failure_reason = ?, failure_detail = ?,
                                result_prefix = ?, finished_at = now(), updated_at = now()
                            WHERE id = ? AND status = 'COLLECTING_RESULTS'
                            """)
                    .params(to.name(), exitCode, failureReason, failureDetail, resultPrefix, jobId)
                    .update();
            if (rows == 0) {
                return false;
            }
            recordEvent(jobId, JobStatus.COLLECTING_RESULTS, to, null, failureReason);
            return true;
        });
    }

    /** Failed from any active state; used when an unexpected error interrupts the executor. */
    public boolean fail(UUID jobId, String failureReason, String failureDetail) {
        return tx.execute(status -> {
            int rows = jdbc.sql("""
                            UPDATE jobs
                            SET status = 'FAILED', failure_reason = ?, failure_detail = ?,
                                finished_at = now(), updated_at = now()
                            WHERE id = ? AND status IN ('PROVISIONING', 'RUNNING', 'COLLECTING_RESULTS')
                            """)
                    .params(failureReason, failureDetail, jobId)
                    .update();
            if (rows == 0) {
                return false;
            }
            recordEvent(jobId, null, JobStatus.FAILED, null, failureReason + ": " + failureDetail);
            return true;
        });
    }

    public Optional<Job> find(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, name, runtime, timeout_seconds, script_key, status, instance_state,
                               instance_id, ssm_command_id, attempt_count, exit_code, failure_reason,
                               failure_detail, result_prefix, created_at, started_at, finished_at
                        FROM jobs WHERE id = ?
                        """)
                .param(jobId)
                .query((rs, i) -> new Job(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("name"),
                        rs.getString("runtime"),
                        rs.getInt("timeout_seconds"),
                        rs.getString("script_key"),
                        JobStatus.valueOf(rs.getString("status")),
                        InstanceState.valueOf(rs.getString("instance_state")),
                        rs.getString("instance_id"),
                        rs.getString("ssm_command_id"),
                        rs.getInt("attempt_count"),
                        getInteger(rs, "exit_code"),
                        rs.getString("failure_reason"),
                        rs.getString("failure_detail"),
                        rs.getString("result_prefix"),
                        toInstant(rs, "created_at"),
                        toInstant(rs, "started_at"),
                        toInstant(rs, "finished_at")
                ))
                .optional();
    }

    private static Integer getInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant toInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private void recordEvent(UUID jobId, JobStatus from, JobStatus to, InstanceState instanceState, String detail) {
        jdbc.sql("""
                INSERT INTO job_events (job_id, from_status, to_status, instance_state, detail)
                VALUES (?, ?, ?, ?, ?)
                """)
                .params(jobId, from == null ? null : from.name(), to == null ? null : to.name(),
                        instanceState == null ? null : instanceState.name(), detail)
                .update();
    }
}
