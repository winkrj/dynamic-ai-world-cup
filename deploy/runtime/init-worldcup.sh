#!/bin/sh
set -eu
# Runs only for a new, empty PostgreSQL volume. Never alter an existing database.
# Read the password from the environment inside psql, not a CLI argument/SQL echo.
psql --no-psqlrc --no-password --username postgres --dbname postgres --set ON_ERROR_STOP=1 <<'SQL'
\getenv app_password WORLDCUP_PASSWORD
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
CREATE ROLE worldcup LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
CREATE DATABASE worldcup OWNER worldcup;
REVOKE CONNECT ON DATABASE worldcup FROM PUBLIC;
GRANT CONNECT ON DATABASE worldcup TO worldcup;
\connect worldcup
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO worldcup;
SQL
