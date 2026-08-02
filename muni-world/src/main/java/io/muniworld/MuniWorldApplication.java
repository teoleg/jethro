package io.muniworld;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * muni-world — an independent Spring Boot application in the jethro repo. It reuses the shared jethro
 * {@code common-*} libraries but ships as its own jar on its own port (default 8090), with its own ADR
 * flow ({@code muni-world/docs/adr}) and README. See ADR-0001.
 *
 * <p>{@code @EnableScheduling} powers the ADR-0014 audio capture loop — harmless when that bean is absent
 * (it's OFF by default; only present when {@code muni.audio.capture.enabled=true} on the Pi).
 */
@SpringBootApplication
@EnableScheduling
public class MuniWorldApplication {
    public static void main(String[] args) {
        SpringApplication.run(MuniWorldApplication.class, args);
    }
}
