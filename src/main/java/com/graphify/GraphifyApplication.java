package com.graphify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/** Sign-in goes through LoginService only; Boot's default in-memory user must not exist. */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class GraphifyApplication {

	public static void main(String[] args) {
		SpringApplication.run(GraphifyApplication.class, args);
	}

}
