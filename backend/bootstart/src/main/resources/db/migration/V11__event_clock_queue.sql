-- Strict work shares the run-local keys and lifecycle of existing scheduled input.
ALTER TABLE simulation_scheduled_event ADD COLUMN work_kind TEXT NOT NULL DEFAULT 'EXTERNAL_INPUT'
    CHECK (work_kind IN ('EXTERNAL_INPUT', 'DOMAIN_EVENT'));
ALTER TABLE simulation_scheduled_event ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;
-- Preserve enqueue order across forks even when new audit sequences are assigned.
ALTER TABLE simulation_scheduled_event ADD COLUMN enqueue_sequence INTEGER NOT NULL DEFAULT 0;
ALTER TABLE simulation_scheduled_event ADD COLUMN work_status TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (work_status IN ('PENDING', 'PROCESSING', 'AWAITING_ACTION', 'COMPLETED', 'FAILED', 'CANCELLED'));
ALTER TABLE simulation_scheduled_event ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE simulation_scheduled_event ADD COLUMN completed_sequence INTEGER;

-- Existing rows retain their legacy delivery semantics; their runs never switch clock contracts.
UPDATE simulation_scheduled_event SET enqueue_sequence=created_sequence,
    work_status=CASE WHEN delivered=1 THEN 'COMPLETED' ELSE 'PENDING' END;

CREATE INDEX idx_scheduled_work ON simulation_scheduled_event (
    run_id, work_status, simulation_time, priority DESC, enqueue_sequence, event_id
);
