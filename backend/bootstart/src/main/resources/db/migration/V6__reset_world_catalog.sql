-- One-time user-requested reset of legacy worlds and their simulations.
-- Accounts and encrypted model configurations deliberately remain intact.
DELETE FROM simulation_branch;
DELETE FROM simulation_scheduled_event;
DELETE FROM simulation_command;
DELETE FROM simulation_snapshot;
DELETE FROM simulation_event;
DELETE FROM simulation_run;
DELETE FROM scenario;
DELETE FROM world_relation;
DELETE FROM world_entity;
DELETE FROM world_definition;

-- New runs retain their initial world definition independently of editable templates.
ALTER TABLE simulation_run ADD COLUMN world_json TEXT CHECK (world_json IS NULL OR JSON_VALID(world_json));
