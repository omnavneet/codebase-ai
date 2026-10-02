package com.codebaseai.backend.controller;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * Locks in the contract the ALB/ECS health checks depend on: 200 with
 * {@code status=UP} when the database answers, 503 with {@code status=DOWN}
 * when it does not - a reachable process with a dead database must not be
 * reported healthy, or the load balancer keeps sending it traffic.
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private JdbcOperations jdbcOperations;

    @Test
    void reportsUpWhenTheDatabaseAnswers() {
        when(jdbcOperations.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        ResponseEntity<Map<String, String>> response = new HealthController(jdbcOperations).health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("UP", response.getBody().get("database"));
    }

    @Test
    void reportsServiceUnavailableWhenTheDatabaseIsDown() {
        when(jdbcOperations.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("database unreachable"));

        ResponseEntity<Map<String, String>> response = new HealthController(jdbcOperations).health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("database"));
    }
}