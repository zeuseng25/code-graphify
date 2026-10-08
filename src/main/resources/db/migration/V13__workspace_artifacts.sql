-- V13: artifacts of scanned repositories installed for each other, and nested Maven projects (Plan 16)
ALTER TABLE scm_repository ADD (last_installed_commit VARCHAR2(64 BYTE));
ALTER TABLE maven_module ADD (packaging VARCHAR2(40 BYTE));
ALTER TABLE index_run_repo ADD (artifact_install VARCHAR2(20 BYTE), artifact_install_error CLOB);
ALTER TABLE index_run_repo ADD CONSTRAINT ck_index_run_repo_install CHECK (artifact_install IS NULL
    OR artifact_install IN ('INSTALLED', 'UP_TO_DATE', 'FAILED', 'CYCLE_FAILED'));
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.pom_search_depth', '3', 'INT', 'Kökte pom.xml yoksa alt klasörlerde aranacak en fazla derinlik', 1, 10);
