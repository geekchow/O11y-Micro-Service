package com.o11y.shop.payment;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ChargeController {

    private static final Logger log = LoggerFactory.getLogger(ChargeController.class);

    private final JdbcTemplate jdbc;
    private final double workSeconds;

    public ChargeController(JdbcTemplate jdbc,
                            @Value("${shop.charge-db-seconds:0.35}") double workSeconds) {
        this.jdbc = jdbc;
        this.workSeconds = workSeconds;
    }

    public record ChargeRequest(String orderId, long amountCents) {}

    @PostMapping("/charge")
    public ResponseEntity<Map<String, Object>> charge(@RequestBody ChargeRequest req) {
        // Simulated card processor: amounts ending in 7 are declined (~10% of traffic).
        if (req.amountCents() % 10 == 7) {
            Span span = Span.current();
            span.setStatus(StatusCode.ERROR, "card_declined");
            span.setAttribute("payment.decline_reason", "card_declined");
            log.warn("charge declined for order {} (amount {} cents)", req.orderId(), req.amountCents());
            return ResponseEntity.status(402).body(Map.of("orderId", req.orderId(), "status", "declined"));
        }

        try {
            chargeDb(req.orderId(), req.amountCents());
        } catch (DataAccessException e) {
            Span span = Span.current();
            span.setStatus(StatusCode.ERROR, "db_connection_unavailable");
            span.recordException(e);
            log.error("charge failed for order {}: no DB connection within Hikari timeout", req.orderId(), e);
            return ResponseEntity.status(503).body(Map.of("orderId", req.orderId(), "status", "error"));
        }

        log.info("charge captured for order {} ({} cents)", req.orderId(), req.amountCents());
        return ResponseEntity.ok(Map.of("orderId", req.orderId(), "status", "captured"));
    }

    // pg_sleep stands in for the card processor round-trip, deliberately holding
    // the pooled connection for the whole charge — the contention the incident needs.
    @WithSpan("charge-db")
    void chargeDb(String orderId, long amountCents) {
        jdbc.update(
                "INSERT INTO payments (order_id, amount_cents, status) SELECT ?, ?, 'captured' FROM pg_sleep(?)",
                orderId, amountCents, workSeconds);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }
}
