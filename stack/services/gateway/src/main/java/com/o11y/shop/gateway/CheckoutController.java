package com.o11y.shop.gateway;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@RestController
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    // Business-level instrumentation: the part auto-instrumentation cannot know.
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("shop.gateway");
    private final LongCounter ordersPlaced = GlobalOpenTelemetry.getMeter("shop.gateway")
            .counterBuilder("orders_placed")
            .setDescription("Orders accepted at checkout")
            .setUnit("{order}")
            .build();

    private final RestClient auth;
    private final RestClient cart;
    private final RestClient payment;

    public CheckoutController(
            @Value("${shop.auth-url}") String authUrl,
            @Value("${shop.cart-url}") String cartUrl,
            @Value("${shop.payment-url}") String paymentUrl) {
        this.auth = client(authUrl);
        this.cart = client(cartUrl);
        this.payment = client(paymentUrl);
    }

    private static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(10_000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record CheckoutRequest(String cartId, long amountCents, String tenant) {}

    @PostMapping("/checkout")
    public ResponseEntity<Map<String, Object>> checkout(@RequestBody CheckoutRequest req) {
        Span span = tracer.spanBuilder("checkout").startSpan();
        try (Scope spanScope = span.makeCurrent();
             Scope baggageScope = Baggage.current().toBuilder()
                     .put("tenant.id", req.tenant() == null ? "unknown" : req.tenant())
                     .build().makeCurrent()) {

            auth.get().uri("/verify").retrieve().toBodilessEntity();

            Map<?, ?> cartBody = cart.get().uri("/cart/{id}", req.cartId()).retrieve().body(Map.class);
            Object rawCount = cartBody == null ? null : cartBody.get("itemCount");
            int itemCount = rawCount instanceof Number n ? n.intValue() : 0;
            span.setAttribute("cart.item_count", itemCount);

            String orderId = UUID.randomUUID().toString();
            try {
                payment.post().uri("/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("orderId", orderId, "amountCents", req.amountCents()))
                        .retrieve().toBodilessEntity();
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 402) {
                    span.setStatus(StatusCode.ERROR, "card_declined");
                    log.warn("checkout {} declined by payment service", orderId);
                    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                            .body(Map.of("orderId", orderId, "status", "declined"));
                }
                throw e;
            }

            ordersPlaced.add(1);
            log.info("checkout {} completed: {} items, {} cents", orderId, itemCount, req.amountCents());
            return ResponseEntity.ok(Map.of("orderId", orderId, "status", "paid"));
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }
}
