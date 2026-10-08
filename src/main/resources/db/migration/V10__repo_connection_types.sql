-- V10: repository connection types beyond Bitbucket (spec 2026-10-07 §3)
ALTER TABLE scm_connection DROP CONSTRAINT ck_scm_connection_type;
ALTER TABLE scm_connection ADD CONSTRAINT ck_scm_connection_type CHECK (type IN ('BITBUCKET_DC', 'GITHUB', 'GIT'));
-- GIT connections: the clone URLs, one per line
ALTER TABLE scm_connection ADD (repository_urls CLOB);
