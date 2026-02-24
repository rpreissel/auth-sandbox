ALTER TABLE device_login.devices
    ADD COLUMN user_id VARCHAR(255),
    ADD COLUMN name    VARCHAR(255);

-- Back-fill with the device_id so existing rows remain valid (best-effort for dev environments).
UPDATE device_login.devices SET user_id = device_id, name = device_id WHERE user_id IS NULL;

ALTER TABLE device_login.devices
    ALTER COLUMN user_id SET NOT NULL,
    ALTER COLUMN name    SET NOT NULL;

CREATE UNIQUE INDEX idx_devices_user_id ON device_login.devices(user_id);
