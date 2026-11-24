-- One database per service: each service owns its data, and no other service
-- may read or write it. They share a Postgres server here only to save memory
-- on a laptop; in production each could have its own.
CREATE DATABASE wallet;
CREATE DATABASE auth;
