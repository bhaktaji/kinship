package com.kinship.common.health;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public HealthController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean db = databaseUp();
        boolean cache = redisUp();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("db", db);
        body.put("redis", cache);
        return ResponseEntity.status(db && cache ? 200 : 503).body(body);
    }

    private boolean databaseUp() {
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean redisUp() {
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
