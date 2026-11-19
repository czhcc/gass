-- Versioned Action drafts and immutable published definitions. World/run JSON stores bound snapshots.
CREATE TABLE action_library (
  id TEXT PRIMARY KEY,
  domain_code TEXT NOT NULL,
  owner_type TEXT NOT NULL,
  action_type TEXT NOT NULL,
  revision INTEGER NOT NULL,
  entry_json TEXT NOT NULL,
  UNIQUE(domain_code, owner_type, action_type, revision)
);
