package com.kinship.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-window counters in Redis. Fails open when Redis is unavailable, as
 * required by docs/lld.md section 8, and logs a warning once per call.
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private final StringRedisTemplate redis;

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void check(String key, int limit, Duration window) {
        String redisKey = "rl:" + key;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, window);
            }
            if (count != null && count > limit) {
                Long ttl = redis.getExpire(redisKey, TimeUnit.SECONDS);
                throw new RateLimitedException(ttl == null || ttl < 1 ? window.toSeconds() : ttl);
            }
        } catch (RateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("rate_limiter_unavailable reason={}", e.getClass().getSimpleName());
        }
    }
}
