-- Reusable world templates. Dynamic definitions are JSON, never executable code.
CREATE TABLE world_definition (
 id TEXT PRIMARY KEY, -- World identifier.
 definition_json TEXT NOT NULL CHECK (JSON_VALID(definition_json)) -- Versioned world definition.
);
-- Relational graph index; scoped entity identifiers cannot escape their world.
CREATE TABLE world_entity (
 world_id TEXT NOT NULL REFERENCES world_definition(id), -- Owning template.
 id TEXT NOT NULL, -- Entity identifier within the world.
 type TEXT NOT NULL, -- Dynamic entity type.
 properties_json TEXT NOT NULL CHECK (JSON_VALID(properties_json)), -- Dynamic properties.
 PRIMARY KEY(world_id,id)
);
CREATE TABLE world_relation (
 world_id TEXT NOT NULL, -- Owning world.
 id TEXT NOT NULL, -- Relation identifier.
 source_id TEXT NOT NULL, -- Source entity.
 target_id TEXT NOT NULL, -- Target entity.
 relation_type TEXT NOT NULL, -- Dynamic relationship type.
 PRIMARY KEY(world_id,id),
 FOREIGN KEY(world_id,source_id) REFERENCES world_entity(world_id,id),
 FOREIGN KEY(world_id,target_id) REFERENCES world_entity(world_id,id)
);
CREATE INDEX idx_relation_source ON world_relation(world_id,source_id);
CREATE INDEX idx_relation_target ON world_relation(world_id,target_id);
-- Editable scenario and immutable per-run copy.
CREATE TABLE scenario (
 id TEXT PRIMARY KEY, -- Scenario identifier.
 definition_json TEXT NOT NULL CHECK (JSON_VALID(definition_json)) -- Agent and budget configuration.
);
CREATE TABLE simulation_run (
 id TEXT PRIMARY KEY, -- Isolated run identifier.
 scenario_json TEXT NOT NULL CHECK (JSON_VALID(scenario_json)), -- Frozen scenario configuration.
 status TEXT NOT NULL, -- Controlled lifecycle status.
 step INTEGER NOT NULL DEFAULT 0, -- Current logical round.
 state_json TEXT NOT NULL CHECK (JSON_VALID(state_json)), -- Current simulated state only.
 created_at INTEGER NOT NULL -- Wall-clock start used for time budget.
);
CREATE TABLE simulation_event (
 run_id TEXT NOT NULL REFERENCES simulation_run(id), -- Event owner.
 sequence_no INTEGER NOT NULL, -- Monotonic per-run sequence.
 event_type TEXT NOT NULL, -- Protocol event kind.
 simulation_time INTEGER NOT NULL, -- Logical clock.
 agent_id TEXT, -- Originating actor, null for runtime.
 payload_json TEXT NOT NULL CHECK (JSON_VALID(payload_json)), -- Public trace without credentials.
 PRIMARY KEY(run_id,sequence_no)
);
CREATE INDEX idx_event_time ON simulation_event(run_id,simulation_time);
CREATE TABLE simulation_snapshot (
 run_id TEXT NOT NULL REFERENCES simulation_run(id), -- Snapshot owner.
 version INTEGER NOT NULL, -- State version after a committed mutation.
 state_json TEXT NOT NULL CHECK (JSON_VALID(state_json)), -- Replay checkpoint, no LLM execution required.
 PRIMARY KEY(run_id,version)
);
CREATE TABLE simulation_command (
 run_id TEXT NOT NULL REFERENCES simulation_run(id), -- Idempotency scope.
 command_id TEXT NOT NULL, -- Client supplied operation key.
 PRIMARY KEY(run_id,command_id)
);
CREATE TABLE model_config (
 id TEXT PRIMARY KEY, -- Model identifier.
 config_json TEXT NOT NULL CHECK (JSON_VALID(config_json)) -- Safe settings; credentials referenced by environment name only.
);
