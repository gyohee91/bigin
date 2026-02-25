package com.ghyinc.finance.global.circuitbreaker;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Circuit Breaker 관리", description = "Circuit Breaker 상태 조회 및 제어")
@RestController
@RequestMapping("/api/admin/circuit-breakers")
@RequiredArgsConstructor
public class CircuitBreakerController {
    private final CircuitBreakerStateService circuitBreakerStateService;

    @Operation(summary = "모든 Circuit Breaker 상태 조회")
    @GetMapping
    public ResponseEntity<Map<String, CircuitBreakerInfo>> getAllCircuitBreakers() {
        return ResponseEntity.ok(circuitBreakerStateService.getAllCircuitBreakers());
    }

    @Operation(summary = "Circuit Breaker 강제 OPEN")
    @PostMapping("/{name}/open")
    public ResponseEntity<String> openCircuitBreaker(@PathVariable String name) {
        circuitBreakerStateService.transitionToOpenState(name);
        return ResponseEntity.ok("Circuit Breaker OPEN: " + name);
    }

    @Operation(summary = "Circuit Breaker 강제 CLOSE")
    @PostMapping("/{name}/close")
    public ResponseEntity<String> closeCircuitBreaker(@PathVariable String name) {
        circuitBreakerStateService.transitionToCloseState(name);
        return ResponseEntity.ok("Circuit Breaker CLOSE: " + name);
    }

    @Operation(summary = "Circuit Breaker 강제 HALF_OPEN")
    @PostMapping("/{name}/half-open")
    public ResponseEntity<String> halfOpenCircuitBreaker(@PathVariable String name) {
        circuitBreakerStateService.transitionToHalfOpenState(name);
        return ResponseEntity.ok("Circuit Breaker HALF_OPEN: " + name);
    }

    @Operation(summary = "Circuit Breaker 초기화")
    @PostMapping("/{name}/reset")
    public ResponseEntity<String> resetCircuitBreaker(@PathVariable String name) {
        circuitBreakerStateService.reset(name);
        return ResponseEntity.ok("Circuit Breaker 초기화: " + name);
    }
}
