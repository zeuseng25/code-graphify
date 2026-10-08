-- V11: GitHub connections may also list the repositories the token's owner owns (spec 2026-10-07 §6)
ALTER TABLE scm_connection ADD (include_own_repositories NUMBER(1) DEFAULT 0 NOT NULL
    CONSTRAINT ck_scm_connection_own_repos CHECK (include_own_repositories IN (0, 1)));
