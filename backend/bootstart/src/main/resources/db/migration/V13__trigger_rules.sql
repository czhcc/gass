-- Rule drafts are editable; published revisions and run snapshots are immutable.
CREATE TABLE simulation_trigger_rule_draft (
    rule_id TEXT PRIMARY KEY,
    world_id TEXT NOT NULL REFERENCES world_definition(id) ON DELETE CASCADE,
    definition_json TEXT NOT NULL CHECK (JSON_VALID(definition_json))
);
CREATE INDEX idx_rule_draft_world ON simulation_trigger_rule_draft (world_id,rule_id);
CREATE TABLE simulation_trigger_rule_revision (
    rule_id TEXT NOT NULL REFERENCES simulation_trigger_rule_draft(rule_id) ON DELETE CASCADE,
    revision INTEGER NOT NULL CHECK (revision>0),
    definition_json TEXT NOT NULL CHECK (JSON_VALID(definition_json)),
    PRIMARY KEY (rule_id,revision)
);

-- Existing runs and legacy queues retain their original behavior.
ALTER TABLE simulation_run ADD COLUMN trigger_rules_json TEXT CHECK (trigger_rules_json IS NULL OR JSON_VALID(trigger_rules_json));
ALTER TABLE simulation_scheduled_event ADD COLUMN auto_action INTEGER NOT NULL DEFAULT 0 CHECK (auto_action IN (0,1));

-- Keep the V10 receipt layout and all existing rows, adding a separate namespace for rule commands.
CREATE TABLE simulation_action_receipt_v13 (
    run_id TEXT NOT NULL REFERENCES simulation_run(id) ON DELETE CASCADE,
    origin TEXT NOT NULL CHECK (origin IN ('AGENT_ACTION','USER_INTERVENTION','SYSTEM_RULE')),
    command_id TEXT NOT NULL,
    action_run_id TEXT NOT NULL,
    request_json TEXT NOT NULL CHECK (JSON_VALID(request_json)),
    receipt_json TEXT NOT NULL CHECK (JSON_VALID(receipt_json)),
    state_version INTEGER NOT NULL,
    simulation_time INTEGER NOT NULL,
    PRIMARY KEY (run_id,origin,command_id),
    UNIQUE (run_id,action_run_id),
    FOREIGN KEY (run_id,state_version) REFERENCES simulation_snapshot(run_id,version)
);
INSERT INTO simulation_action_receipt_v13 (
    run_id,origin,command_id,action_run_id,request_json,receipt_json,state_version,simulation_time
)
SELECT run_id,origin,command_id,action_run_id,request_json,receipt_json,state_version,simulation_time
FROM simulation_action_receipt;
DROP TABLE simulation_action_receipt;
ALTER TABLE simulation_action_receipt_v13 RENAME TO simulation_action_receipt;
