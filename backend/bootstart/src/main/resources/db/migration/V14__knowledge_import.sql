-- Server-owned external query batch. The client can request a commit by candidate_id only.
CREATE TABLE simulation_knowledge_candidate (
    run_id TEXT NOT NULL REFERENCES simulation_run(id) ON DELETE CASCADE,
    candidate_id TEXT NOT NULL,
    provider_code TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    state_version INTEGER NOT NULL,
    lifecycle_sequence INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    request_json TEXT NOT NULL,
    response_json TEXT,
    response_sha256 TEXT,
    PRIMARY KEY (run_id, candidate_id)
);

-- Frozen import receipt and identity mapping. A child stores historical receipts at its fork baseline.
CREATE TABLE simulation_knowledge_import (
    run_id TEXT NOT NULL REFERENCES simulation_run(id) ON DELETE CASCADE,
    candidate_id TEXT NOT NULL,
    provider_code TEXT NOT NULL,
    import_order INTEGER NOT NULL,
    event_sequence INTEGER NOT NULL,
    entity_count INTEGER NOT NULL,
    identities_json TEXT NOT NULL,
    receipt_json TEXT NOT NULL,
    PRIMARY KEY (run_id, candidate_id),
    UNIQUE (run_id, import_order)
);
