#!/bin/bash
# Runs once on first container start (docker-entrypoint-initdb.d).
# Creates the device_login user and schema inside the shared database.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER device_login WITH PASSWORD '${DEVICE_POSTGRES_PASSWORD}';
    CREATE SCHEMA IF NOT EXISTS device_login AUTHORIZATION device_login;
    GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO device_login;
    GRANT USAGE, CREATE ON SCHEMA device_login TO device_login;
    ALTER ROLE device_login SET search_path TO device_login;
EOSQL
