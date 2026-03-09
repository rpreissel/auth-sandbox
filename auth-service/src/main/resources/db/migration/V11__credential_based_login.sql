-- devices: device_id entfernen, neue Spalten hinzufuegen
ALTER TABLE device_login.devices DROP COLUMN IF EXISTS device_id;
ALTER TABLE device_login.devices ADD COLUMN public_key_hash VARCHAR(64);
ALTER TABLE device_login.devices ADD COLUMN enc_pub_key TEXT NOT NULL DEFAULT '';
ALTER TABLE device_login.devices ADD COLUMN device_name VARCHAR(255) NOT NULL DEFAULT '';

-- V4-Migration legte einen UNIQUE INDEX auf user_id an (idx_devices_user_id).
-- Dieser muss gedropt werden, da ein User jetzt mehrere Devices haben kann.
-- (Eindeutigkeit gilt nur pro User+deviceName, nicht pro userId global)
DROP INDEX IF EXISTS device_login.idx_devices_user_id;

CREATE UNIQUE INDEX idx_devices_public_key_hash ON device_login.devices(public_key_hash);
CREATE UNIQUE INDEX idx_devices_user_name ON device_login.devices(user_id, device_name);

-- challenges: device_id entfernen, user_id hinzufuegen, challenge_value nullable
ALTER TABLE device_login.challenges DROP COLUMN IF EXISTS device_id;
ALTER TABLE device_login.challenges ADD COLUMN user_id VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE device_login.challenges ALTER COLUMN challenge_value DROP NOT NULL;
