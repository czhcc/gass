-- Delivery queues a reasoning round before the model runs. Sequences delimit historical visibility.
CREATE TABLE simulation_reasoning_round (
    run_id TEXT NOT NULL REFERENCES simulation_run(id),
    round INTEGER NOT NULL,
    simulation_time INTEGER NOT NULL,
    input_version INTEGER NOT NULL,
    created_sequence INTEGER NOT NULL,
    started_sequence INTEGER,
    completed_sequence INTEGER,
    PRIMARY KEY (run_id, round)
);
CREATE UNIQUE INDEX idx_reasoning_round_open ON simulation_reasoning_round(run_id) WHERE completed_sequence IS NULL;

-- A step key identifies the logical round across retries, including keys used during automatic resume.
ALTER TABLE simulation_command ADD COLUMN reasoning_round INTEGER;

-- Committed decisions survive a restarted planner or a fork of an unfinished round.
CREATE TABLE simulation_reasoning_action (
    run_id TEXT NOT NULL,
    round INTEGER NOT NULL,
    sequence_no INTEGER NOT NULL,
    agent_id TEXT NOT NULL,
    command_id TEXT NOT NULL,
    selector_json TEXT NOT NULL CHECK (JSON_VALID(selector_json)),
    receipt_json TEXT NOT NULL CHECK (JSON_VALID(receipt_json)),
    PRIMARY KEY (run_id, round, command_id),
    FOREIGN KEY (run_id, round) REFERENCES simulation_reasoning_round(run_id, round)
);
