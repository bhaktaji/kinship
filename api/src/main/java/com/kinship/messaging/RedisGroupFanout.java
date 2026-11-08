package com.kinship.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.group.GroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RedisGroupFanout {

    private static final Logger log = LoggerFactory.getLogger(RedisGroupFanout.class);
    private final RedisMessageListenerContainer container;
    private final GroupSessionRegistry registry;
    private final GroupRepository groups;
    private final ObjectMapper objectMapper;
    private final Map<UUID, MessageListener> listeners = new ConcurrentHashMap<>();

    public RedisGroupFanout(RedisMessageListenerContainer container, GroupSessionRegistry registry,
                            GroupRepository groups, ObjectMapper objectMapper) {
        this.container = container;
        this.registry = registry;
        this.groups = groups;
        this.objectMapper = objectMapper;
    }

    public void subscribe(UUID groupId) {
        listeners.computeIfAbsent(groupId, id -> {
            ChannelTopic topic = new ChannelTopic("group:" + id);
            MessageListener listener = (message, pattern) -> onMessage(id, message);
            container.addMessageListener(listener, topic);
            return listener;
        });
    }

    public void unsubscribeIfEmpty(UUID groupId) {
        if (registry.hasSessions(groupId)) {
            return;
        }
        MessageListener listener = listeners.remove(groupId);
        if (listener != null) {
            container.removeMessageListener(listener, new ChannelTopic("group:" + groupId));
        }
    }

    private void onMessage(UUID groupId, Message message) {
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(payload);
            // Membership is re-checked per frame: an open socket is not proof of
            // current authorization after a removal or a leave.
            Set<UUID> activeMembers = new HashSet<>(groups.activeMemberIds(groupId));
            registry.broadcast(groupId, node.path("message_id").asText(null), payload, activeMembers);
        } catch (Exception e) {
            log.warn("message_fanout_failed group_id={} reason={}", groupId, e.getClass().getSimpleName());
        }
    }
}
