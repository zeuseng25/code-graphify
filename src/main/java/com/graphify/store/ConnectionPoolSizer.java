package com.graphify.store;

import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Grows the Hikari pool before index workers start (plan 2 follow-up): each worker holds a connection while it writes,
 * and store.connection_reserve more stay free for API requests. Never shrinks the pool.
 */
@Component
public class ConnectionPoolSizer {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolSizer.class);

    private final DataSource dataSource;
    private final AppSettings settings;

    public ConnectionPoolSizer(DataSource dataSource, AppSettings settings) {
        this.dataSource = dataSource;
        this.settings = settings;
    }

    public int ensureCapacity(int workers) {
        int needed = workers + settings.getInt(SettingKeys.STORE_CONNECTION_RESERVE);
        try {
            if (!dataSource.isWrapperFor(HikariDataSource.class)) {
                return needed;
            }
            HikariConfigMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariConfigMXBean();
            if (pool.getMaximumPoolSize() < needed) {
                log.info("Growing the connection pool from {} to {} for {} index workers", pool.getMaximumPoolSize(),
                        needed, workers);
                pool.setMaximumPoolSize(needed);
            }
            return pool.getMaximumPoolSize();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not size the connection pool", e);
        }
    }
}
