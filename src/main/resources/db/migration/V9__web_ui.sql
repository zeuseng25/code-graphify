-- Plan 9: how often the web UI refreshes a running index run (web UI spec §3.6).

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('ui.poll_interval', 'PT5S', 'DURATION', 'Arayüzün çalışan taramayı yenileme aralığı', NULL, NULL);
