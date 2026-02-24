CREATE TABLE device_login.devices (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id   VARCHAR(255) NOT NULL UNIQUE,
    public_key  TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_devices_device_id ON device_login.devices(device_id);

CREATE TABLE device_login.challenges (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id       VARCHAR(255) NOT NULL,
    challenge_value TEXT         NOT NULL,
    nonce           VARCHAR(255) NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ  NOT NULL,
    used            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_challenges_nonce      ON device_login.challenges(nonce);
CREATE INDEX idx_challenges_device_id  ON device_login.challenges(device_id);
