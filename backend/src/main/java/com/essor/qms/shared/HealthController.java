package com.essor.qms.shared;

import java.util.Map;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/health} (public) : {@code {"status":"UP"}}, 503 sinon. */
@RestController
class HealthController {

    private final HealthEndpoint healthEndpoint;

    HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/api/health")
    ResponseEntity<Map<String, String>> health() {
        Status status = healthEndpoint.health().getStatus();
        return ResponseEntity.status(Status.UP.equals(status) ? 200 : 503)
                .body(Map.of("status", status.getCode()));
    }
}
