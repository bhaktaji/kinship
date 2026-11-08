package com.kinship.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GroupSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(GroupSessionRegistry.class);
    private final Map<UUID, Map<String, WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> delivered = new ConcurrentHashMap<>();

    public void register(UUID groupId, UUID userId, WebSocketSession session) {
        sessions.computeIfAbsent(groupId, key -> new ConcurrentHashMap<>()).put(session.getId(), session);
        session.getAttributes().put("ws.userId", userId);
        session.getAttributes().put("ws.groupId", groupId);
    }

    public void unregister(UUID groupId, String sessionId) {
        Map<String, WebSocketSession> group = sessions.get(groupId);
        if (group != null) {
            group.remove(sessionId);
            if (group.isEmpty()) {
                sessions.remove(groupId);
            }
        }
        delivered.remove(groupId);
    }

    public int activeConnections() {
        return sessions.values().stream().mapToInt(Map::size).sum();
    }

    public boolean hasSessions(UUID groupId) {
        Map<String, WebSocketSession> group = sessions.get(groupId);
        return group != null && !group.isEmpty();
    }

    /**
     * Fan-out to one group. Sessions whose user is no longer an active member are
     * closed instead of receiving the frame, so removing a member stops their live
     * message stream immediately rather than at their next reconnect.
     */
    public void broadcast(UUID groupId, String messageId, String payload, Set<UUID> allowedUserIds) {
        Map<String, WebSocketSession> group = sessions.get(groupId);
        if (group == null || group.isEmpty()) {
            return;
        }
        Set<String> seen = delivered.computeIfAbsent(groupId, key -> ConcurrentHashMap.newKeySet());
        if (messageId != null && !messageId.isBlank() && !seen.add(messageId)) {
            return;
        }
        if (seen.size() > 1000) {
            seen.clear();
        }
        TextMessage frame = new TextMessage(payload.getBytes(StandardCharsets.UTF_8));
        for (WebSocketSession session : group.values()) {
            Object sessionUserId = session.getAttributes().get(TicketHandshakeInterceptor.USER_ID);
            if (allowedUserIds != null
                    && (!(sessionUserId instanceof UUID userId) || !allowedUserIds.contains(userId))) {
                closeQuietly(session);
                continue;
            }
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(frame);
                    }
                }
            } catch (IOException e) {
                closeQuietly(session);
            }
        }
    }

    public void pingAll() {
        for (Map<String, WebSocketSession> group : sessions.values()) {
            for (WebSocketSession session : group.values()) {
                try {
                    synchronized (session) {
                        if (session.isOpen()) {
                            session.sendMessage(new PingMessage());
                        }
                    }
                } catch (IOException e) {
                    closeQuietly(session);
                }
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException e) {
            log.debug("ws_close_failed session_id={}", session.getId());
        }
    }
}
