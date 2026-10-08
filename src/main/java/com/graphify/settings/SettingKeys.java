package com.graphify.settings;

/** Keys of the rows seeded by V2__seed_settings.sql. Values live in the database, never here. */
public final class SettingKeys {

    public static final String INDEX_CRON = "index.cron";
    public static final String INDEX_PARALLELISM = "index.parallelism";
    public static final String INDEX_WORKSPACE_DIR = "index.workspace_dir";
    public static final String INDEX_PARSE_BATCH_SIZE = "index.parse_batch_size";
    public static final String INDEX_MAVEN_TIMEOUT = "index.maven_timeout";
    public static final String INDEX_MAVEN_OUTPUT_TAIL_LINES = "index.maven_output_tail_lines";
    public static final String SCM_RETRY_COUNT = "scm.retry_count";
    public static final String SCM_RETRY_BACKOFF = "scm.retry_backoff";
    public static final String SCM_PAGE_SIZE = "scm.page_size";
    public static final String IMPACT_DEFAULT_DEPTH = "impact.default_depth";
    public static final String IMPACT_MAX_DEPTH = "impact.max_depth";
    public static final String IMPACT_MAX_RESULTS = "impact.max_results";
    public static final String USAGE_SNIPPET_MAX_LENGTH = "usage.snippet_max_length";
    public static final String API_PAGE_DEFAULT_SIZE = "api.page_default_size";
    public static final String API_PAGE_MAX_SIZE = "api.page_max_size";
    public static final String GRAPH_MAX_NODES = "graph.max_nodes";
    public static final String GRAPH_COMMUNITY_SEED = "graph.community_seed";
    public static final String AUTH_SESSION_TIMEOUT = "auth.session_timeout";
    public static final String AUTH_MAX_FAILED_ATTEMPTS = "auth.max_failed_attempts";
    public static final String AUTH_LOCK_DURATION = "auth.lock_duration";
    public static final String CLEANUP_ORPHAN_SYMBOLS_CRON = "cleanup.orphan_symbols_cron";
    public static final String STORE_JDBC_BATCH_SIZE = "store.jdbc_batch_size";
    public static final String INDEX_MAVEN_EXECUTABLE = "index.maven_executable";
    public static final String INDEX_MAVEN_LOCAL_REPOSITORY = "index.maven_local_repository";
    public static final String INDEX_GIT_DEPTH = "index.git_depth";
    public static final String INDEX_GIT_TIMEOUT = "index.git_timeout";
    public static final String INDEX_SOURCE_ROOTS = "index.source_roots";
    public static final String INDEX_POM_SEARCH_DEPTH = "index.pom_search_depth";
    public static final String INDEX_MAVEN_CHECK_TIMEOUT = "index.maven_check_timeout";
    public static final String SCM_CONNECT_TIMEOUT = "scm.connect_timeout";
    public static final String SCM_READ_TIMEOUT = "scm.read_timeout";
    public static final String SCM_MAX_DEACTIVATION_PERCENT = "scm.max_deactivation_percent";
    public static final String CLEANUP_BATCH_SIZE = "cleanup.batch_size";
    public static final String STORE_CONNECTION_RESERVE = "store.connection_reserve";
    public static final String AUTH_PASSWORD_MIN_LENGTH = "auth.password_min_length";
    public static final String AUTH_LDAP_CONNECT_TIMEOUT = "auth.ldap_connect_timeout";
    public static final String AUTH_LDAP_READ_TIMEOUT = "auth.ldap_read_timeout";
    public static final String AUTH_LDAP_SEARCH_MAX_RESULTS = "auth.ldap_search_max_results";
    public static final String ARTIFACT_TEST_TIMEOUT = "artifact.test_timeout";
    public static final String GRAPH_COMMUNITY_MAX_ITERATIONS = "graph.community_max_iterations";
    public static final String GRAPH_REPORT_TOP_N = "graph.report_top_n";
    public static final String GRAPH_EXPORT_MAX_NODES = "graph.export_max_nodes";
    public static final String UI_POLL_INTERVAL = "ui.poll_interval";

    private SettingKeys() {
    }
}
