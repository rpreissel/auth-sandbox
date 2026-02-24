-- Stores server-side PKCE state for SSO transfer sessions.
-- The code_verifier is kept here instead of inside the Transfer-JWT so that
-- it is never exposed in a URL (browser history, Referer header, access logs).
CREATE TABLE device_login.transfer_sessions (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      VARCHAR(255) NOT NULL UNIQUE,
    code_verifier   TEXT         NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    redeemed        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_transfer_sessions_session_id ON device_login.transfer_sessions(session_id);
CREATE INDEX idx_transfer_sessions_expires_at ON device_login.transfer_sessions(expires_at);
