-- Plan 7: one outcome per repository and run, artifact repository test results and the artifact test timeout.

DELETE FROM index_run_repo a
 WHERE EXISTS (SELECT 1 FROM index_run_repo b WHERE b.run_id = a.run_id AND b.repo_id = a.repo_id AND b.id < a.id);
ALTER TABLE index_run_repo ADD CONSTRAINT uq_index_run_repo UNIQUE (run_id, repo_id);

ALTER TABLE artifact_repository ADD (
    last_test_status VARCHAR2(30 BYTE),
    last_test_at     TIMESTAMP WITH TIME ZONE
);

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('artifact.test_timeout', 'PT10S', 'DURATION', 'Maven deposu bağlantı testi zaman aşımı', NULL, NULL);
