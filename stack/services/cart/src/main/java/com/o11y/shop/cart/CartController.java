package com.o11y.shop.cart;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class CartController {

    private final RestClient inventory;

    public CartController(@Value("${shop.inventory-url}") String inventoryUrl) {
        this.inventory = RestClient.create(inventoryUrl);
    }

    @GetMapping("/cart/{id}")
    public Map<String, Object> cart(@PathVariable String id) {
        int itemCount = Math.floorMod(id.hashCode(), 4) + 1;
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            String sku = "SKU-" + id + "-" + i;
            Map<?, ?> stock = inventory.get().uri("/stock/{sku}", sku).retrieve().body(Map.class);
            Object rawAvailable = stock == null ? null : stock.get("available");
            int available = rawAvailable instanceof Number n ? n.intValue() : 0;
            items.add(Map.of("sku", sku, "qty", 1, "available", available));
        }
        return Map.of("cartId", id, "itemCount", itemCount, "items", items);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }
}
