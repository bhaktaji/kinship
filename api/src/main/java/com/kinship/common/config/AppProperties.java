package com.kinship.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Auth auth, List<String> corsOrigins, Seed seed, Realtime realtime) {

    public record Jwt(String secret, String issuer, Duration accessTtl) {
    }

    public record Auth(Duration refreshTtl, boolean cookieSecure) {
    }

    public record Seed(boolean enabled) {
    }

    public record Realtime(Duration ticketTtl) {
    }
}
