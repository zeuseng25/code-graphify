-- Default values for spec §6.3 settings. These rows are the only place defaults live; change them in the UI.
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.cron', '0 0 2 * * *', 'CRON', 'Tam tarama zamanlaması (saniye dakika saat gün ay haftagünü)', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.parallelism', '4', 'INT', 'Aynı anda taranan repo sayısı', 1, 32);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.workspace_dir', '/data/impact-analyzer/repos', 'STRING', 'Repoların klonlandığı çalışma dizini', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.parse_batch_size', '500', 'INT', 'Tek seferde JDT ile parse edilen dosya sayısı', 1, 10000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.maven_timeout', 'PT10M', 'DURATION', 'Maven classpath çözümleme zaman aşımı (ISO-8601)', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('index.maven_output_tail_lines', '50', 'INT', 'Hata kaydına yazılan Maven çıktısı satır sayısı', 1, 1000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.retry_count', '3', 'INT', 'SCM API hatalarında yeniden deneme sayısı', 0, 10);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.retry_backoff', 'PT2S', 'DURATION', 'İlk yeniden deneme bekleme süresi (üstel artar)', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.page_size', '100', 'INT', 'SCM API sayfa boyutu', 1, 1000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('impact.default_depth', '3', 'INT', 'Etki analizi varsayılan derinliği', 1, 10);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('impact.max_depth', '10', 'INT', 'Etki analizi azami derinliği', 1, 50);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('impact.max_results', '5000', 'INT', 'Etki analizinde döndürülen azami sembol sayısı', 1, 100000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('usage.snippet_max_length', '500', 'INT', 'Kullanım satırı önizlemesinin azami karakter sayısı', 1, 1000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('api.page_default_size', '50', 'INT', 'API varsayılan sayfa boyutu', 1, 500);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('api.page_max_size', '500', 'INT', 'API azami sayfa boyutu', 1, 5000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.max_nodes', '500', 'INT', 'Repo grafında bir seviyede gösterilen azami düğüm', 10, 5000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.community_seed', '42', 'INT', 'Topluluk tespiti için sabit rastgelelik tohumu', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.session_timeout', 'PT8H', 'DURATION', 'Oturum zaman aşımı', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.max_failed_attempts', '5', 'INT', 'Hesap kilitlenmeden önce izin verilen hatalı giriş', 1, 100);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('auth.lock_duration', 'PT15M', 'DURATION', 'Hesap kilit süresi', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('cleanup.orphan_symbols_cron', '0 0 4 * * SUN', 'CRON', 'Kullanılmayan sembolleri temizleme zamanlaması', NULL, NULL);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('store.jdbc_batch_size', '1000', 'INT', 'Veritabanına tek seferde yazılan satır sayısı', 1, 10000);
