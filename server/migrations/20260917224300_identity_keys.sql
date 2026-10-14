ALTER TABLE users RENAME COLUMN public_key TO identity_key_dh;
ALTER TABLE users ADD COLUMN identity_key_sign TEXT NOT NULL DEFAULT '';
