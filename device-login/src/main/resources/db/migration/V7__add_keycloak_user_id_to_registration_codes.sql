-- V7: Add keycloak_user_id to registration_codes.
-- The Keycloak user is now created when the admin provisions a registration
-- code rather than when the device is registered. This column stores the
-- Keycloak-internal UUID so that DeviceService can reuse the pre-created user
-- instead of creating a new one.
ALTER TABLE device_login.registration_codes
    ADD COLUMN keycloak_user_id VARCHAR(36);
