package com.graphify;

import org.springframework.boot.SpringApplication;

/** Runs the application locally against a throwaway Oracle container: {@code ./mvnw spring-boot:test-run}. */
public class TestGraphifyApplication {

    public static void main(String[] args) {
        SpringApplication.from(GraphifyApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
