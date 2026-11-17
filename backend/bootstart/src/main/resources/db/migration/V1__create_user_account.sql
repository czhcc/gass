-- SQLite does not support COMMENT ON TABLE/COLUMN. Comments are preserved here.
-- Table user_account: studio login accounts.
CREATE TABLE user_account (
    id INTEGER PRIMARY KEY AUTOINCREMENT, -- Account identifier.
    username VARCHAR(255) NOT NULL UNIQUE, -- Unique login name.
    password_hash VARCHAR(255) NOT NULL -- BCrypt password hash; never plaintext.
);
