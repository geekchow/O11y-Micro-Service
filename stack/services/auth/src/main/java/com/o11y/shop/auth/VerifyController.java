package com.o11y.shop.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class VerifyController {

    private static final Logger log = LoggerFactory.getLogger(VerifyController.class);

    @GetMapping("/verify")
    public Map<String, Object> verify() throws InterruptedException {
        Thread.sleep(20 + ThreadLocalRandom.current().nextLong(20));
        log.debug("token verified");
        return Map.of("ok", true, "user", "demo");
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }
}
