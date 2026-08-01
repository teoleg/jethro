package io.muniworld;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * muni-world — an independent Spring Boot application in the jethro repo. It reuses the shared jethro
 * {@code common-*} libraries but ships as its own jar on its own port (default 8090), with its own ADR
 * flow ({@code muni-world/docs/adr}) and README. See ADR-0001.
 */
@SpringBootApplication
public class MuniWorldApplication {
    public static void main(String[] args) {
        SpringApplication.run(MuniWorldApplication.class, args);
    }
}
