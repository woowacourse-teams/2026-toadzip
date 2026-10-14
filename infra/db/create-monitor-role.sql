-- Run once on each PostgreSQL instance, connected to postgres as its administrator.
-- Set a distinct password interactively with: \password toadzip_monitor
CREATE ROLE toadzip_monitor LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION CONNECTION LIMIT 5;
GRANT CONNECT ON DATABASE postgres TO toadzip_monitor;
GRANT pg_read_all_stats TO toadzip_monitor;
ALTER ROLE toadzip_monitor SET default_transaction_read_only = on;
ALTER ROLE toadzip_monitor SET statement_timeout = '5s';
ALTER ROLE toadzip_monitor SET lock_timeout = '1s';
