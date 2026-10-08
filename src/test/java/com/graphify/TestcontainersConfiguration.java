package com.graphify;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/** One Oracle Free container per test JVM; Spring Boot wires the datasource to it. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    OracleContainer oracleContainer() {
        return new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"));
    }
}
