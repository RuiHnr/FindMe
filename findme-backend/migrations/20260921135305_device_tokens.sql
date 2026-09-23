CREATE TABLE IF NOT EXISTS device_tokens (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    fcm_token varchar,
    apns_token varchar
);
