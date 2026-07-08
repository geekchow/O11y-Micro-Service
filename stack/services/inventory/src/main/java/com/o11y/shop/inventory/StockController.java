package com.o11y.shop.inventory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class StockController {

    @GetMapping("/stock/{sku}")
    public Map<String, Object> stock(@PathVariable String sku) throws InterruptedException {
        Thread.sleep(5 + ThreadLocalRandom.current().nextLong(10));
        return Map.of("sku", sku, "available", 42);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }
}
