-- Task-scoped proposals use the existing candidate table and never share the operator query path.
ALTER TABLE simulation_knowledge_candidate ADD COLUMN task_id TEXT;
ALTER TABLE simulation_knowledge_candidate ADD COLUMN coverage_revision INTEGER;
ALTER TABLE simulation_knowledge_candidate ADD COLUMN import_requested INTEGER NOT NULL DEFAULT 0;

-- Revocations are durable for a run and inherited by branches at the selected sequence.
CREATE TABLE simulation_knowledge_revocation (
    run_id TEXT NOT NULL REFERENCES simulation_run(id) ON DELETE CASCADE,
    role_id TEXT NOT NULL,
    object_id TEXT NOT NULL,
    event_sequence INTEGER NOT NULL,
    PRIMARY KEY (run_id, role_id, object_id)
);
