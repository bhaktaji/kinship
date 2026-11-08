package com.kinship.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.activity.dto.ActivityCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
public class DiscoveryCache {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryCache.class);
    private static final Duration TTL = Duration.ofSeconds(120);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public DiscoveryCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Optional<List<ActivityCard>> get(String key) {
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, new TypeReference<List<ActivityCard>>() { }));
        } catch (Exception e) {
            log.warn("discovery_cache_read_failed key={} reason={}", key, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void put(String key, List<ActivityCard> cards) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(cards), TTL);
        } catch (Exception e) {
            log.warn("discovery_cache_write_failed key={} reason={}", key, e.getClass().getSimpleName());
        }
    }

    public Duration ttl() {
        return TTL;
    }
}
