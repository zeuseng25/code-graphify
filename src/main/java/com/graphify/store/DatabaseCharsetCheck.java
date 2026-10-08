package com.graphify.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to start against a database whose character set is not AL32UTF8. Every text column is sized in bytes and
 * every writer truncates by UTF-8 byte length, and Java identifiers may contain Turkish (or any Unicode) letters; a
 * single-byte character set would make that byte arithmetic wrong and corrupt such identifiers.
 */
@Component
class DatabaseCharsetCheck {

    static final String REQUIRED_CHARSET = "AL32UTF8";

    DatabaseCharsetCheck(JdbcTemplate jdbc) {
        requireUtf8(jdbc.queryForObject(
                "SELECT value FROM nls_database_parameters WHERE parameter = 'NLS_CHARACTERSET'", String.class));
    }

    static void requireUtf8(String charset) {
        if (!REQUIRED_CHARSET.equals(charset)) {
            throw new IllegalStateException("Oracle database character set is " + charset + ", but " + REQUIRED_CHARSET
                    + " is required: column widths are byte counts computed from UTF-8, and identifiers with Turkish"
                    + " letters (ç, ğ, ı, ö, ş, ü) cannot be stored faithfully in another character set.");
        }
    }
}
