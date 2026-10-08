-- Plan 5: the index lock, run cancellation and errors, in-progress markers, connection sync status and
-- orchestration settings.

CREATE TABLE index_lock (
    lock_name   VARCHAR2(30 BYTE) NOT NULL,
    holder      VARCHAR2(100 BYTE),
    acquired_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_index_lock PRIMARY KEY (lock_name)
);
INSERT INTO index_lock (lock_name) VALUES ('INDEX');

ALTER TABLE index_run ADD (
    cancel_requested NUMBER(1) DEFAULT 0 NOT NULL,
    error            VARCHAR2(4000 BYTE)
);
ALTER TABLE index_run ADD CONSTRAINT ck_index_run_cancel CHECK (cancel_requested IN (0, 1));

ALTER TABLE scm_repository ADD (indexing_run_id NUMBER(19));
ALTER TABLE scm_repository ADD CONSTRAINT fk_scm_repository_indexing_run
    FOREIGN KEY (indexing_run_id) REFERENCES index_run (id);

ALTER TABLE scm_connection ADD (
    last_sync_status VARCHAR2(30 BYTE),
    last_sync_at     TIMESTAMP WITH TIME ZONE,
    last_sync_error  VARCHAR2(4000 BYTE)
);
ALTER TABLE scm_connection ADD CONSTRAINT ck_scm_connection_sync CHECK (last_sync_status IS NULL
    OR last_sync_status IN ('SUCCESS', 'AUTH_FAILED', 'FAILED', 'DEACTIVATION_SKIPPED'));

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('scm.max_deactivation_percent', '50', 'INT',
     'Bir senkronda pasife alınabilecek aktif repo oranı (%); aşılırsa pasife alma atlanır, 100 = sınırsız', 0, 100);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('cleanup.batch_size', '10000', 'INT', 'Yetim sembol temizliğinde bir DELETE ile silinen en fazla satır', 1, 1000000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('store.connection_reserve', '5', 'INT', 'Tarama işçileri dışında API için boş tutulan veritabanı bağlantısı', 1, 100);
