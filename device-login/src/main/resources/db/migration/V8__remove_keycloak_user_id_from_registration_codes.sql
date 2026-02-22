-- V8: Remove keycloak_user_id from registration_codes.
-- The Keycloak user is now looked up by username on-demand instead of being
-- stored as a UUID in the registration_codes table.
ALTER TABLE device_login.registration_codes DROP COLUMN IF EXISTS keycloak_user_id;
