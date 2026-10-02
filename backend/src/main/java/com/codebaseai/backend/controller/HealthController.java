package com.codebaseai.backend.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Unauthenticated readiness probe for load balancers and container orchestrators
 * (ALB target-group health checks and ECS deploy stability waits). It sits at
 * {@code /api/health} so it is reachable both directly and through the frontend's
 * nginx {@code /api} proxy, and it exposes up/down state only - never any data.
 *
 * <p>A process that is running but cannot serve requests is not healthy: every
 * real endpoint needs the database, so the probe runs a trivial {@code SELECT 1}
 * and answers 503 when Postgres is unreachable or the pool is exhausted. The load
 * balancer then drains the task instead of routing traffic to it.
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcOperations jdbcOperations;

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        try {
            jdbcOperations.queryForObject("SELECT 1", Integer.class);
        } catch (RuntimeException e) {
            log.warn("Health probe failed: database not reachable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(status("DOWN", "DOWN"));
        }
        return ResponseEntity.ok(status("UP", "UP"));
    }

    /** LinkedHashMap keeps the field order stable for curl/log readability. */
    private Map<String, String> status(String overall, String database) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", overall);
        body.put("database", database);
        return body;
    }
}