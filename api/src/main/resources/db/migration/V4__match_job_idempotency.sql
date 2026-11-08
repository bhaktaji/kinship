-- P3: one proposal per matching job, making worker retries idempotent.
CREATE UNIQUE INDEX uq_match_groups_job ON match_groups (job_id);
