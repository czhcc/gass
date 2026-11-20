-- Durable, versioned parent/child tasks; old runs remain readable without destructive cleanup.
CREATE TABLE simulation_task (
  run_id TEXT NOT NULL REFERENCES simulation_run(id) ON DELETE CASCADE,
  task_id TEXT NOT NULL,
  parent_task_id TEXT,
  agent_id TEXT NOT NULL,
  round INTEGER NOT NULL,
  status TEXT NOT NULL,
  input_version INTEGER NOT NULL,
  output_version INTEGER NOT NULL,
  request_json TEXT NOT NULL CHECK (JSON_VALID(request_json)),
  result_json TEXT NOT NULL CHECK (JSON_VALID(result_json)),
  created_sequence INTEGER NOT NULL,
  completed_sequence INTEGER,
  PRIMARY KEY(run_id,task_id)
);
CREATE INDEX idx_task_parent ON simulation_task(run_id,parent_task_id);
-- Request accounting is shared by master and workers; tokens are conservatively reserved.
CREATE TABLE simulation_usage (
  run_id TEXT PRIMARY KEY REFERENCES simulation_run(id) ON DELETE CASCADE,
  requests INTEGER NOT NULL DEFAULT 0,
  reserved_tokens INTEGER NOT NULL DEFAULT 0,
  outcome_json TEXT CHECK (outcome_json IS NULL OR JSON_VALID(outcome_json))
);
