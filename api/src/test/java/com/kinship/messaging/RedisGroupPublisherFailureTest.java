package com.kinship.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.messaging.MessageRepository.MessageRow;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

class RedisGroupPublisherFailureTest {

    @Test
    void publishSwallowsRedisFailure() {
        StringRedisTemplate unavailable = new StringRedisTemplate() {
            @Override
            public Long convertAndSend(String channel, Object message) {
                throw new IllegalStateException("redis down");
            }
        };
        RedisGroupPublisher publisher = new RedisGroupPublisher(unavailable, new ObjectMapper());
        MessageRow row = new MessageRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "hello", 1, UUID.randomUUID(), Instant.now());
        assertThatCode(() -> publisher.publish(row)).doesNotThrowAnyException();
    }
}
