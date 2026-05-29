package com.sashkomusic.monitoring;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CircuitBreakerMonitoringService {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Scheduled(fixedRate = 60000) // Every minute
    public void monitorCircuitBreakers() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(circuitBreaker -> {
            CircuitBreaker.State state = circuitBreaker.getState();

            if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.HALF_OPEN) {
                log.warn("⚠️ Circuit Breaker '{}' is in {} state. Metrics: {}",
                    circuitBreaker.getName(),
                    state,
                    circuitBreaker.getMetrics());
            }
        });
    }
}
