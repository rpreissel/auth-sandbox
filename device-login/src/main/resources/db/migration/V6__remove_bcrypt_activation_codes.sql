-- V6: Remove registration codes that were stored with BCrypt hashes.
-- After switching from BCrypt to plaintext storage, any row whose
-- activation_code starts with the BCrypt prefix '$2a$' is unusable
-- (the app now compares codes with equals(), not matches()).
-- Deleting them forces re-provisioning with plaintext codes.
DELETE FROM device_login.registration_codes
WHERE activation_code LIKE '$2a$%';
