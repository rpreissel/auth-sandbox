-- Replace the single-use flag with time-based expiry and a use counter.
-- Codes are now valid until expires_at and can be used any number of times
-- within that window.
--
-- Also removes the UNIQUE constraint on devices.user_id because multiple
-- devices may now register under the same userId (one per use of the code).

ALTER TABLE device_login.registration_codes
    DROP COLUMN IF EXISTS used,
    DROP COLUMN IF EXISTS used_at,
    ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '24 hours'),
    ADD COLUMN use_count  INTEGER      NOT NULL DEFAULT 0;

-- Multiple devices can now share the same user_id (one per code use).
DROP INDEX IF EXISTS device_login.idx_devices_user_id;
ALTER TABLE device_login.devices
    ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE device_login.devices
    ALTER COLUMN user_id SET NOT NULL;
-- Re-add without UNIQUE so multiple devices may share a userId.
CREATE INDEX idx_devices_user_id ON device_login.devices(user_id);
