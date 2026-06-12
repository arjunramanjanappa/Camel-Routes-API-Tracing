package com.arjun.tracer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for TraceGuard — the Camel route trace / impact / verification utility.
 *
 * <p>This is a standalone Spring Boot application. It does NOT host the
 * enterprise framework's CamelContext; instead it is pointed at a source
 * directory and statically analyses the Camel XML routes + REST controllers
 * found there to reconstruct the route execution flow.
 */
@SpringBootApplication
public class TracerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TracerApplication.class, args);
    }
}
