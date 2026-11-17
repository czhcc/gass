-- Logical clock origin is separate from per-run budgeted round count.
ALTER TABLE simulation_run ADD COLUMN base_time INTEGER NOT NULL DEFAULT 0;
