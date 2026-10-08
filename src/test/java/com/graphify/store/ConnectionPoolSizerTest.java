package com.graphify.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.graphify.OracleIntegrationTest;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConnectionPoolSizerTest extends OracleIntegrationTest {

    @Autowired
    ConnectionPoolSizer sizer;

    @Autowired
    DataSource dataSource;

    @Autowired
    AppSettings settings;

    @Test
    void growsThePoolToWorkersPlusTheReserveAndNeverShrinksIt() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        int reserve = settings.getInt(SettingKeys.STORE_CONNECTION_RESERVE);
        int before = hikari.getHikariConfigMXBean().getMaximumPoolSize();

        int grown = sizer.ensureCapacity(before + 3);

        assertThat(grown).isEqualTo(before + 3 + reserve);
        assertThat(hikari.getHikariConfigMXBean().getMaximumPoolSize()).isEqualTo(before + 3 + reserve);
        assertThat(sizer.ensureCapacity(1)).isEqualTo(before + 3 + reserve);
    }
}
