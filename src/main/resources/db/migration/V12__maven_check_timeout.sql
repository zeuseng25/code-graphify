-- V12: how long saving index.maven_executable waits for "<value> -v" (Plan 15)
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.maven_check_timeout', 'PT30S', 'DURATION',
     'Maven komutu kaydedilirken "-v" denemesinin zaman aşımı (ISO-8601)', NULL, NULL);
