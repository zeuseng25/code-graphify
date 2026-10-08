-- Plan 8: stored repository graph analyses (spec §9.2, §9.3) and the graph settings they read.

CREATE TABLE repo_graph_analysis (
    repo_id         NUMBER(19)                             NOT NULL,
    commit_hash     VARCHAR2(64 BYTE)                      NOT NULL,
    analyzed_at     TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    class_count     NUMBER(10)                             NOT NULL,
    community_count NUMBER(10)                             NOT NULL,
    cycle_count     NUMBER(10)                             NOT NULL,
    CONSTRAINT pk_repo_graph_analysis PRIMARY KEY (repo_id),
    CONSTRAINT fk_repo_graph_analysis_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id)
);

CREATE TABLE repo_graph_community (
    repo_id         NUMBER(19)          NOT NULL,
    commit_hash     VARCHAR2(64 BYTE)   NOT NULL,
    symbol_id       NUMBER(19)          NOT NULL,
    community_id    NUMBER(10)          NOT NULL,
    community_label VARCHAR2(2000 BYTE) NOT NULL,
    CONSTRAINT pk_repo_graph_community PRIMARY KEY (repo_id, symbol_id),
    CONSTRAINT fk_repo_graph_community_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id),
    CONSTRAINT fk_repo_graph_community_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (id) ON DELETE CASCADE
);

CREATE INDEX ix_repo_graph_community_symbol ON repo_graph_community (symbol_id);

CREATE TABLE repo_graph_metric (
    repo_id        NUMBER(19) NOT NULL,
    symbol_id      NUMBER(19) NOT NULL,
    in_degree      NUMBER(10) NOT NULL,
    out_degree     NUMBER(10) NOT NULL,
    dependents     NUMBER(10) NOT NULL,
    is_entry_point NUMBER(1)  NOT NULL,
    CONSTRAINT pk_repo_graph_metric PRIMARY KEY (repo_id, symbol_id),
    CONSTRAINT fk_repo_graph_metric_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id),
    CONSTRAINT fk_repo_graph_metric_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (id) ON DELETE CASCADE,
    CONSTRAINT ck_repo_graph_metric_entry CHECK (is_entry_point IN (0, 1))
);

CREATE INDEX ix_repo_graph_metric_symbol ON repo_graph_metric (symbol_id);

CREATE TABLE repo_graph_cycle (
    repo_id      NUMBER(19)          NOT NULL,
    cycle_id     NUMBER(10)          NOT NULL,
    package_name VARCHAR2(2000 BYTE) NOT NULL,
    CONSTRAINT pk_repo_graph_cycle PRIMARY KEY (repo_id, cycle_id, package_name),
    CONSTRAINT fk_repo_graph_cycle_repo FOREIGN KEY (repo_id) REFERENCES scm_repository (id)
);

INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.community_max_iterations', '100', 'INT', 'Topluluk tespitinde azami etiket yayılımı turu', 1, 10000);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.report_top_n', '20', 'INT', 'Graf raporunda listelenen kritik sınıf, topluluk ve giriş noktası sayısı',
     1, 500);
INSERT INTO app_setting (setting_key, setting_value, value_type, description, min_value, max_value) VALUES
    ('graph.export_max_nodes', '20000', 'INT', 'Graf dışa aktarımında azami düğüm', 10, 200000);
