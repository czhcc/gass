-- Completed computation time only; paused and pre-start waiting never count.
-- Old runs start accounting from this upgrade; historical durations cannot be inferred.
ALTER TABLE simulation_run ADD COLUMN active_runtime_ms INTEGER NOT NULL DEFAULT 0;
-- Null on legacy terminal records whose precise termination cause was not recorded.
ALTER TABLE simulation_run ADD COLUMN termination_reason TEXT;
