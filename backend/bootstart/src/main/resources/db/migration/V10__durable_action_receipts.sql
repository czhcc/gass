-- A committed Action command and the exact receipt/state returned when it was handled.
CREATE TABLE simulation_action_receipt (
    run_id TEXT NOT NULL REFERENCES simulation_run(id) ON DELETE CASCADE, -- Owning run.
    origin TEXT NOT NULL CHECK (origin IN ('AGENT_ACTION', 'USER_INTERVENTION')), -- Command namespace.
    command_id TEXT NOT NULL, -- Stable idempotency key within the namespace.
    action_run_id TEXT NOT NULL, -- Shared audit identity for this attempt.
    request_json TEXT NOT NULL CHECK (JSON_VALID(request_json)), -- Compared on retry.
    receipt_json TEXT NOT NULL CHECK (JSON_VALID(receipt_json)), -- First response to the worker.
    state_version INTEGER NOT NULL, -- Snapshot for the first response.
    simulation_time INTEGER NOT NULL, -- The original clock can be later than the snapshot clock.
    PRIMARY KEY (run_id, origin, command_id),
    UNIQUE (run_id, action_run_id),
    FOREIGN KEY (run_id, state_version) REFERENCES simulation_snapshot(run_id, version)
);
