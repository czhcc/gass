-- Branch lineage and retry-safe fork operations.
CREATE TABLE simulation_branch (
 run_id TEXT PRIMARY KEY REFERENCES simulation_run(id), -- Independent child state and events.
 parent_run_id TEXT NOT NULL REFERENCES simulation_run(id), -- Immutable ancestor reference.
 parent_sequence INTEGER NOT NULL, -- Exact source event checkpoint.
 label TEXT NOT NULL, -- Human-readable intervention label.
 command_key TEXT NOT NULL, -- Fork idempotency key scoped to parent.
 UNIQUE(parent_run_id,command_key)
);
-- Retain injection order so historical forks cannot inherit events injected later.
ALTER TABLE simulation_scheduled_event ADD COLUMN created_sequence INTEGER NOT NULL DEFAULT 0;
