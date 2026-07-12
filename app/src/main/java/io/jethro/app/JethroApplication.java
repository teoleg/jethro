package io.jethro.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single-JVM assembly of all Jethro modules (ADR-0015). Modules are wired in here and
 * nowhere else; cross-domain flow goes through Redpanda topics even in-process, so any
 * module can later be extracted to its own main() without contract changes.
 */
@SpringBootApplication
public class JethroApplication {

    public static void main(String[] args) {
        SpringApplication.run(JethroApplication.class, args);
    }
}
