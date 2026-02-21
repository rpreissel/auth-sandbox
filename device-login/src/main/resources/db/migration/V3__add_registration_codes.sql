-- Pre-provisioned registration entries created by an admin.
-- Each entry allows exactly one device to register using the matching activation code.
CREATE TABLE device_login.registration_codes (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         VARCHAR(255) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    activation_code VARCHAR(255) NOT NULL,   -- BCrypt hash of the static activation password
    used            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    used_at         TIMESTAMPTZ
);

CREATE INDEX idx_registration_codes_user_id ON device_login.registration_codes(user_id);
