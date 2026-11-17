-- User event queue; injection never directly changes world entities or relations.
CREATE TABLE simulation_scheduled_event (
 run_id TEXT NOT NULL REFERENCES simulation_run(id), -- Isolated owner.
 event_id TEXT NOT NULL, -- Per-run idempotency key.
 simulation_time INTEGER NOT NULL, -- Logical due time.
 payload_json TEXT NOT NULL CHECK (JSON_VALID(payload_json)), -- Event input, not executable code.
 delivered INTEGER NOT NULL DEFAULT 0, -- One-time delivery marker.
 PRIMARY KEY(run_id,event_id)
);
CREATE INDEX idx_scheduled_time ON simulation_scheduled_event(run_id,delivered,simulation_time);
