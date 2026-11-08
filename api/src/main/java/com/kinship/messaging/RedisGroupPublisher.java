package com.kinship.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RedisGroupPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisGroupPublisher.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisGroupPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void publish(MessageRepository.MessageRow message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "msg");
            payload.put("message_id", message.id().toString());
            payload.put("group_id", message.groupId().toString());
            payload.put("sequence_id", message.sequenceId());
            payload.put("sender_id", message.senderId().toString());
            payload.put("body", message.body());
            payload.put("created_at", message.createdAt().toString());
            redis.convertAndSend("group:" + message.groupId(), objectMapper.writeValueAsString(payload));
        } catch (RuntimeException e) {
            log.warn("message_publish_failed group_id={} reason={}", message.groupId(), e.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("message_publish_failed group_id={} reason={}", message.groupId(), e.getClass().getSimpleName());
        }
    }
}
